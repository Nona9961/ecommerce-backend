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
 * 写后自读窗口路由集成测试（TD-08：窗口命中 → 本次查询走主库）。
 * <p>
 * 测试策略（决策 a 同构既有搜索集成测试）：replica 数据源以 H2 内存库模拟（MODE=
 * PostgreSQL）承载「CDC 尚未同步的旧行集」；主库（H2 mem:mydb，@Primary
 * 回填模板）承载「含新写入行的权威行集」——两库 product_search_view
 * 模拟表同构，fixture 差异即「写后未同步」差异：主库多出 1010 行
 * （商家刚保存、CDC 未达镜像），窗口命中时搜索应经主库通道返回 1010
 * （read-your-writes），窗口外/匿名照常走 replica（不返回 1010）。
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
     * 被测搜索服务
     */
    @Autowired
    private ProductSearchService productSearchService;

    /**
     * replica 数据源 JdbcTemplate（旧行集 fixture：模拟 CDC 未同步的
     * PG 镜像视图）
     */
    @Autowired
    @Qualifier("replicaJdbcTemplate")
    private JdbcTemplate replicaJdbcTemplate;

    /**
     * 主库 JdbcTemplate（@Primary 回填 bean；权威行集 fixture：含新写
     * 入行 1010）
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
     * 重建两库 product_search_view 模拟表：replica 3 行（旧基线），
     * 主库 4 行（旧基线 + 1010 新写入）。
     * <pre>
     * id    name                  price    sales  shop    brand   cat   time
     * 101   Apple iPhone 15 手机   499900   120    9001 店铺A 501 品牌1 101  t1
     * 102   机械键盘                79900   900    9003 店铺C 504 品牌4 103  t2
     * 103   索尼降噪耳机            99900   1000   9003 店铺C 502 品牌2 103  t3
     * 1010  Apple iPhone 16 Pro   599900     0    9001 店铺A 501 品牌1 101  t4（仅主库）
     * </pre>
     * 「Apple」命中 101（replica/主库）与 1010（仅主库）——窗口命中时
     * total=2 且含 1010，窗口外 total=1 且不含 1010。
     */
    @BeforeEach
    void setUpViews() {
        dropAndCreateView(replicaJdbcTemplate);
        dropAndCreateView(primaryJdbcTemplate);

        replicaJdbcTemplate.execute("""
                INSERT INTO product_search_view VALUES
                (101, 'Apple iPhone 15 手机', '5G 旗舰手机', '/files/101.jpg', 499900, 120, 10, 9001, '店铺A', 501, '品牌1', 101, TIMESTAMP '2026-08-01 10:00:00'),
                (102, '机械键盘',             '办公外设',     '/files/102.jpg',  79900, 900, 90, 9003, '店铺C', 504, '品牌4', 103, TIMESTAMP '2026-08-02 10:00:00'),
                (103, '索尼降噪耳机',         '无线蓝牙耳机',  '/files/103.jpg',  99900, 1000, 50, 9003, '店铺C', 502, '品牌2', 103, TIMESTAMP '2026-08-03 10:00:00')
                """);
        primaryJdbcTemplate.execute("""
                INSERT INTO product_search_view VALUES
                (101, 'Apple iPhone 15 手机', '5G 旗舰手机', '/files/101.jpg', 499900, 120, 10, 9001, '店铺A', 501, '品牌1', 101, TIMESTAMP '2026-08-01 10:00:00'),
                (102, '机械键盘',             '办公外设',     '/files/102.jpg',  79900, 900, 90, 9003, '店铺C', 504, '品牌4', 103, TIMESTAMP '2026-08-02 10:00:00'),
                (103, '索尼降噪耳机',         '无线蓝牙耳机',  '/files/103.jpg',  99900, 1000, 50, 9003, '店铺C', 502, '品牌2', 103, TIMESTAMP '2026-08-03 10:00:00'),
                (1010, 'Apple iPhone 16 Pro', '新品旗舰手机', '/files/1010.jpg', 599900, 0,   60, 9001, '店铺A', 501, '品牌1', 101, TIMESTAMP '2026-08-04 10:00:00')
                """);
    }

    /**
     * 双库同构建表（列契约与部署位视图一致）。
     *
     * @param jdbcTemplate 目标库模板
     */
    private static void dropAndCreateView(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS product_search_view");
        jdbcTemplate.execute("""
                CREATE TABLE product_search_view (
                    product_id     BIGINT PRIMARY KEY,
                    name           VARCHAR(128)  NOT NULL,
                    description    VARCHAR(4000),
                    cover_image_url VARCHAR(512),
                    min_price      BIGINT        NOT NULL,
                    sales_total    BIGINT        NOT NULL,
                    available_total BIGINT       NOT NULL,
                    shop_id        BIGINT        NOT NULL,
                    shop_name      VARCHAR(128)  NOT NULL,
                    brand_id       BIGINT,
                    brand_name     VARCHAR(64),
                    category_id    BIGINT,
                    updated_at     TIMESTAMP     NOT NULL
                )""");
    }

    /**
     * 用例后清理主库模拟表（WU-53 基线契约）：product_search_view 是主库上的
     * 测试自建读模型模拟表（replica 面为 JVM 内 H2 无需清），残留会破坏
     * MigrationAcTest 基线「无多余业务表」精确断言——每用例重建语义不变，
     * 库面保持干净。
     */
    @AfterEach
    void tearDownPrimaryView() {
        primaryJdbcTemplate.execute("DROP TABLE IF EXISTS product_search_view");
    }

    // ---------- happy path ----------

    /**
     * 窗口命中 → 主库：写者本人 3s 内搜到自己刚写入的内容
     * （1010 仅主库存在，CDC 未同步——window hit 时仍可见）。
     */
    @Test
    @DisplayName("窗口命中：搜索走主库，写者可见刚写入的商品（read-your-writes）")
    void search_windowHit_readsPrimary_seesOwnWrite() {
        when(searchWriteWindow.isWithinWriteWindow(WRITER_UID)).thenReturn(true);

        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, null, null, null, null),
                new PageQuery(1, 10), WRITER_UID);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.records()).extracting(ProductCard::productId)
                .contains(101L, 1010L);
    }

    // ---------- critical path ----------

    /**
     * 窗口外 → 读库：他人/过期窗口读 PG 镜像（1010 不可见，可能旧一秒）。
     */
    @Test
    @DisplayName("窗口外：搜索走 replica，不见主库新写入")
    void search_windowMiss_readsReplica() {
        when(searchWriteWindow.isWithinWriteWindow(WRITER_UID)).thenReturn(false);

        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, null, null, null, null),
                new PageQuery(1, 10), WRITER_UID);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(101L);
    }

    /**
     * 匿名（无账号 2 参重载，既有契约保留）：不判定窗口，走 replica。
     */
    @Test
    @DisplayName("匿名搜索：2 参重载走 replica，行为与既有 2 参入口一致")
    void search_anonymous_readsReplica() {
        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, null, null, null, null),
                new PageQuery(1, 10));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(101L);
    }

    /**
     * 账号缺失（3 参 uid=null）：无窗口判定，走 replica。
     */
    @Test
    @DisplayName("uid=null：无窗口判定，走 replica")
    void search_nullUid_readsReplica() {
        final PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("Apple", null, null, null, null, null, null),
                new PageQuery(1, 10), null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(101L);
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
                new SearchCriteria("Apple", null, null, null, null, null, null),
                new PageQuery(1, 10), WRITER_UID);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).extracting(ProductCard::productId)
                .containsExactly(101L);
    }
}