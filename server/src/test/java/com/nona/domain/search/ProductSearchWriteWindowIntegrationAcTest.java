package com.nona.domain.search;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.ProductSearchService;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchWriteWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 写后自读窗口路由集成测试（窗口命中 → 本次查询走主库；
 * 2026-09-11 真视图化修订：test 搜索走真实 PG——双库不再自建模拟表）。
 * <p>
 * 测试策略（真实双库差异构造）：CDC sink 全量监听三库（MySQL ecommerce_test
 * 业务表变更同步 PG ecommerce_test 镜像表），「主库有可见新行 / 镜像库没有」
 * 无法依赖 CDC 未同步的瞬时状态——本测试以<b>单一写入源 + 确定性等待面</b>
 * 构造差异：
 * <ul>
 *     <li>fixture 一律从 MySQL 业务表写入（真实写路径），PG 侧行集是 CDC
 *         同步的镜像——杜绝「手动插 PG 与 tombstone 同 key 互踩」的竞态
 *         （DELETE 传播与手动 INSERT 乱序会导致 replica 行集不确定）；</li>
 *     <li>@BeforeEach：主库插 3 行 fixture（99101/99102/99103，店 99002）→
 *         poll-until PG 镜像收敛（3 行齐 + 99110 不存——tombstone 队列排空），
 *         replica 侧「无写后新行」形态由此确定；</li>
 *     <li>窗口命中用例（专属）：追加插 99110（Apple iPhone 16 Pro）→ MySQL
 *         实时视图（部署位同构 DDL）立即聚合 → primary 通道 total=2
 *         （read-your-writes 语义落在主库通道，不依赖 CDC 延迟）；</li>
 *     <li>窗口外/匿名/null/降级四用例：replica 通道断言 total=1（只有 99101
 *         「Apple」命中）——PG 镜像行集由 poll 等待面钉死，确定性成立。</li>
 * </ul>
 * 视图均为部署位真实聚合（PG 镜像视图 / MySQL 实时视图），不再是模拟表承载的
 * 宽松直读——写后可见性（主库视图聚合含新行）与窗口外镜像一致（replica 无新行）
 * 均经真实 SQL 路径。
 * <p>
 * 场景三分：
 * <ul>
 *     <li>happy：窗口命中 → 主库（写者见到自己刚写入的内容 + 既有
 *         商品同构返回）；</li>
 *     <li>critical：窗口外 / 匿名（无账号）/ uid=null → replica；
 *         3s 边界判定由 RedisSearchWriteWindowUnitTest 单测覆盖；</li>
 *     <li>fail：判定设施故障（降级 false 语义）→ replica（可能旧一秒，
 *         可接受）。</li>
 * </ul>
 * 红阶段：窗口路由未实现（UnsupportedOperationException），失败原因 =
 * 实现缺失，非装配错误。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductSearchWriteWindowIntegrationAcTest {

    /**
     * 写者账号（窗口判定主体）
     */
    private static final long WRITER_UID = 1001L;

    /**
     * fixture 独占店铺（主库写入侧；断言圈定锚点——视图聚合全库商品，
     * 无圈定断言会被 seed/其他测试数据污染）
     */
    private static final long FIXTURE_SHOP_ID = 99002L;

    /**
     * 被测搜索服务
     */
    @Autowired
    private ProductSearchService productSearchService;

    /**
     * replica 数据源 JdbcTemplate（PG 镜像等待面：poll 收敛断言用）
     */
    @Autowired
    @Qualifier("replicaJdbcTemplate")
    private JdbcTemplate replicaJdbcTemplate;

    /**
     * 主库 JdbcTemplate（@Primary 回填 bean；MySQL 业务表 fixture 注入——
     * 真视图实时聚合承载「新写入立即可见」）
     */
    @Autowired
    private JdbcTemplate primaryJdbcTemplate;

    /**
     * 写后窗口判定端口（mock：路由决策可控；真实 3s 判定由
     * RedisSearchWriteWindowUnitTest 覆盖）
     */
    @MockitoBean
    private SearchWriteWindow searchWriteWindow;

    /**
     * 每用例前：主库插 3 行 fixture（店 99002 + 99101 Apple/99102 键盘/
     * 99103 耳机，MySQL 业务表）→ 等 PG 镜像收敛（3 行齐 + 99110 不存，
     * 即上一用例 tombstone 队列排空）——replica 侧行集确定性钉死。
     */
    @BeforeEach
    void setUpPrimaryFixture() {
        primaryCleanup();
        insertPrimaryRows();
        awaitReplicaConverged();
    }

    /**
     * 用例后清理：主库 4 行全段幂等删除（MySQL 即时生效；PG 侧经 CDC
     * tombstone 同步随之收敛——下一用例 poll 等待面兜底）。
     */
    @AfterEach
    void tearDownPrimaryFixture() {
        primaryCleanup();
    }

    /**
     * 幂等清理主库 fixture 段（product 99101-99110 + sku 51101-51110 +
     * inventory 91201-91210 + shop 99002；未插时无操作）。
     */
    private void primaryCleanup() {
        primaryJdbcTemplate.execute("""
                DELETE FROM inventory_item WHERE sku_id BETWEEN 51101 AND 51110""");
        primaryJdbcTemplate.execute("""
                DELETE FROM product_sku WHERE id BETWEEN 51101 AND 51110""");
        primaryJdbcTemplate.execute("""
                DELETE FROM product WHERE id BETWEEN 99101 AND 99110""");
        primaryJdbcTemplate.execute("""
                DELETE FROM shop WHERE id = 99002""");
    }

    /**
     * 主库插入基础 fixture（3 行：99101/99102/99103，店 99002）——真实写路径；
     * PG 镜像侧由 CDC 同步（等待面 {@link #awaitReplicaConverged()} 钉死）。
     */
    private void insertPrimaryRows() {
        primaryJdbcTemplate.execute("""
                INSERT INTO shop (id, create_time, update_time, name, status) VALUES
                (99002, '2026-08-01 09:00:00', '2026-08-01 09:00:00', '写窗Fi店铺', 'NORMAL')""");
        primaryJdbcTemplate.execute("""
                INSERT INTO product (id, name, description, status, shop_id, brand_id, category_id, tenant_id, create_time, update_time) VALUES
                (99101, 'Apple iPhone 15 手机', '5G 旗舰手机',      'ON_SALE', 99002, NULL, 101, '99002', '2026-08-01 10:00:00', '2026-08-01 10:00:00'),
                (99102, '机械键盘',             '办公外设',          'ON_SALE', 99002, NULL, 103, '99002', '2026-08-02 10:00:00', '2026-08-02 10:00:00'),
                (99103, '索尼降噪耳机',         '无线蓝牙耳机',      'ON_SALE', 99002, NULL, 103, '99002', '2026-08-03 10:00:00', '2026-08-03 10:00:00')""");
        primaryJdbcTemplate.execute("""
                INSERT INTO product_sku (id, product_id, enabled, price, spec_hash, spec_summary, tenant_id, create_time, update_time) VALUES
                (51101, 99101, TRUE, 499900, 'w101', 'Fi黑色', '99002', '2026-08-01 10:00:00', '2026-08-01 10:00:00'),
                (51102, 99102, TRUE,  79900, 'w102', 'Fi白色', '99002', '2026-08-02 10:00:00', '2026-08-02 10:00:00'),
                (51103, 99103, TRUE,  99900, 'w103', 'Fi黑色', '99002', '2026-08-03 10:00:00', '2026-08-03 10:00:00')""");
        primaryJdbcTemplate.execute("""
                INSERT INTO inventory_item (id, sku_id, available, held, sold, version, tenant_id, create_time, update_time) VALUES
                (91201, 51101, 10, 0, 120,  0, '99002', '2026-08-01 10:00:00', '2026-08-01 10:00:00'),
                (91202, 51102, 20, 0, 900,  0, '99002', '2026-08-02 10:00:00', '2026-08-02 10:00:00'),
                (91203, 51103, 30, 0, 1000, 0, '99002', '2026-08-03 10:00:00', '2026-08-03 10:00:00')""");
    }

    /**
     * 等待 PG 镜像收敛：断言面的同构形状（product_search_view 视图聚合，
     * shop/sku/inventory 跨 topic 收敛慢于 product 单表——等待面必须与
     * 断言面同构，否则视图 JOIN 掉行造成 total 偏差）：基础 3 行齐（店
     * 99002）+ 99110 不存（上用例 tombstone 队列排空）。
     * <p>
     * 稳定拍检测：单次采样可能命中「上用例残留行」——上用例 @AfterEach
     * 的 tombstone（E1 删行）与本周 INSERT（E2 插行）乱序处理时，单拍会
     * 过早放行（E1 未处理时残留行读作 3 行 → E1 处理时查询落入空洞 →
     * total=0）。要求连续两拍（间隔 500ms）条件均成立才放行，E1+E2
     * 窗口（CDC 秒级）在拍间完成，杜绝空洞穿透。
     */
    private void awaitReplicaConverged() {
        final long deadline = System.currentTimeMillis() + 30_000;
        boolean prevHit = false;
        while (true) {
            final Long base = replicaJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_search_view WHERE shop_id = ? "
                            + "AND product_id IN (99101, 99102, 99103)",
                    Long.class, FIXTURE_SHOP_ID);
            final Long extra = replicaJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM product_search_view WHERE product_id = 99110",
                    Long.class);
            final boolean hit = base != null && base == 3 && extra != null && extra == 0;
            if (hit && prevHit) {
                return;
            }
            prevHit = hit;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("PG 镜像视图面未收敛（base=" + base + ", extra=" + extra
                        + "）——sink 队列停滞或跨 topic 未同步");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待中断", e);
            }
        }
    }

    /**
     * 追加「仅主库可见的新写内容」行（窗口命中用例专属）：99110 Apple
     * iPhone 16 Pro 及 sku/inventory 支撑行——MySQL 实时视图立即聚合，
     * 主库通道 read-your-writes 语义由此承载。
     */
    private void insertExtraRow() {
        primaryJdbcTemplate.execute("""
                INSERT INTO product (id, name, description, status, shop_id, brand_id, category_id, tenant_id, create_time, update_time) VALUES
                (99110, 'Apple iPhone 16 Pro', '新品旗舰手机',      'ON_SALE', 99002, NULL, 101, '99002', '2026-08-04 10:00:00', '2026-08-04 10:00:00')""");
        primaryJdbcTemplate.execute("""
                INSERT INTO product_sku (id, product_id, enabled, price, spec_hash, spec_summary, tenant_id, create_time, update_time) VALUES
                (51110, 99110, TRUE, 599900, 'w110', 'Fi黑色', '99002', '2026-08-04 10:00:00', '2026-08-04 10:00:00')""");
        primaryJdbcTemplate.execute("""
                INSERT INTO inventory_item (id, sku_id, available, held, sold, version, tenant_id, create_time, update_time) VALUES
                (91210, 51110, 60, 0, 0,    0, '99002', '2026-08-04 10:00:00', '2026-08-04 10:00:00')""");
    }

    // ---------- happy path ----------

    /**
     * 窗口命中 → 主库：写者本人 3s 内搜到自己刚写入的内容
     * （99110 仅主库业务表存在，MySQL 实时视图聚合——窗口命中时可见；
     * replica 断言不涉及：CDC 同步后的镜像行集不影响本用例）。
     */
    @Test
    @DisplayName("窗口命中：搜索走主库，写者可见刚写入的商品（read-your-writes）")
    void search_windowHit_readsPrimary_seesOwnWrite() {
        insertExtraRow();
        when(searchWriteWindow.isWithinWriteWindow(WRITER_UID)).thenReturn(true);

        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10), WRITER_UID);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.records()).extracting(ProductCard::productId)
                .contains(99101L, 99110L);
    }

    // ---------- critical path ----------

    /**
     * 窗口外 → 读库：他人/过期窗口读 PG 镜像视图（99110 不可见——等待面
     * 已钉死 replica 3 行确定形态，「Apple」仅命中 99101）。
     */
    @Test
    @DisplayName("窗口外：搜索走 replica，不见主库新写入")
    void search_windowMiss_readsReplica() {
        when(searchWriteWindow.isWithinWriteWindow(WRITER_UID)).thenReturn(false);

        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10), WRITER_UID);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(99101L);
    }

    /**
     * 匿名（无账号 2 参重载，既有契约保留）：不判定窗口，走 replica。
     */
    @Test
    @DisplayName("匿名搜索：2 参重载走 replica，行为与既有 2 参入口一致")
    void search_anonymous_readsReplica() {
        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(99101L);
    }

    /**
     * 账号缺失（3 参 uid=null）：无窗口判定，走 replica。
     */
    @Test
    @DisplayName("uid=null：无窗口判定，走 replica")
    void search_nullUid_readsReplica() {
        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10), null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(99101L);
    }

    // ---------- fail path ----------

    /**
     * 判定设施故障（端口承诺降级 false，不抛异常）→ 本次查询照常走
     * replica：窗口失效回落「可能旧一秒」，可用性不依赖窗口组件。
     */
    @Test
    @DisplayName("窗口设施故障：降级走 replica，可用性不受影响")
    void search_windowDown_degradesToReplica() {
        when(searchWriteWindow.isWithinWriteWindow(WRITER_UID)).thenReturn(false);

        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10), WRITER_UID);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(99101L);
    }
}