package com.nona.domain.search;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.ProductSearchService;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品搜索服务集成测试（WU-41 一期：PG 读库 + product_search_view 查询服务；
 * 2026-09-11 真视图化修订：test 搜索走真实 PG——不再自建模拟表）。
 * <p>
 * 测试策略（修订契约）：replica 数据源 = 真 PostgreSQL（ecommerce_test 库，
 * application-test.yml），查询对象 = 部署位真实视图 {@code product_search_view}
 * （PG DDL：镜像表 JOIN 聚合，在售 × 有货 × 有定价）。fixture 直插镜像底层表
 * （shop/brand/product/product_sku/product_image/inventory_item），由视图执行
 * 真实聚合——聚合/过滤/排序语义与生产同构，不再依赖模拟表承载的宽松列契约。
 * <p>
 * 隔离纪律（跨测试耦合防御）：断言一律圈定独占店铺 {@link #FIXTURE_SHOP_ID}
 * （fixture 专属段，不与 seed 97001/其他 AcTest 随机店冲突）——视图聚合全库
 * 商品（含其他测试/seed 在售数据），无圈定断言会被库内噪声污染；fixture 各表
 * 主键使用固定专属段（product 101-109 / sku 50101+ / image 80101+ / inventory
 * 90201+ / brand 9901-9904），用例后逐表清理，库面恢复（verify.sh 对账一致）。
 * <p>
 * 场景三分：
 * <ul>
 *     <li>happy：关键词命中（大小写不敏感/描述命中）、过滤组合
 *         （类目+品牌+店铺+价格）、排序三轴（销量/价格/时间）精确序；</li>
 *     <li>critical：空结果、分页边界（末页/越界页）、价格单点与半开区间；</li>
 *     <li>error：非法价格区间（负值/倒挂）按
 *         {@code search.invalid_price_range} 400 拒绝。</li>
 * </ul>
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductSearchServiceIntegrationAcTest {

    /**
     * fixture 独占店铺（所有 fixture 商品归属；断言圈定锚点）
     */
    private static final long FIXTURE_SHOP_ID = 99001L;

    /**
     * fixture 商品 id 段（product 101-109）
     */
    private static final long PRODUCT_BASE = 100L;

    /**
     * fixture sku id 段（50101-50109）
     */
    private static final long SKU_BASE = 50100L;

    /**
     * fixture 图 id 段（80101-80109）
     */
    private static final long IMAGE_BASE = 80100L;

    /**
     * fixture 库存 id 段（90201-90209）
     */
    private static final long INVENTORY_BASE = 90200L;

    /**
     * 被测搜索服务
     */
    @Autowired
    private ProductSearchService productSearchService;

    /**
     * replica 数据源 JdbcTemplate（真 PG 镜像底层表 fixture 注入）
     */
    @Autowired
    @Qualifier("replicaJdbcTemplate")
    private JdbcTemplate replicaJdbcTemplate;

    /**
     * 向 PG 镜像底层表构建 fixture 行集（9 商品 = 关键词矩阵 × 过滤维度 ×
     * 排序三轴可断言），由真实视图聚合产出与既有契约一致的行集。
     * <pre>
     * id  name                     desc             price    sales  shop  brand  cat   time
     * 101 Apple iPhone 15 手机     5G 旗舰摄影手机   499900   120   99001 FiB1  101  t1
     * 102 苹果平板 iPad 10         平板电脑           299900   300   99001 FiB1  101  t2
     * 103 小米手机 15              摄影爱好者的选择   199900   500   99001 FiB2  101  t3
     * 104 联想笔记本               轻薄办公本         599900    80   99001 FiB3  102  t4
     * 105 索尼降噪耳机             无线蓝牙耳机        99900  1000   99001 FiB2  103  t5
     * 106 Apple Watch 手表         智能手表           249900     0   99001 FiB1  101  t6
     * 107 华为智慧屏               家庭影院摄影画质   399900    60   99001 FiB4  104  t7
     * 108 联想平板 watch 伴侣      平板               149900   250   99001 FiB3  102  t8
     * 109 机械键盘                 办公外设            79900   900   99001 FiB4  103  t9
     * </pre>
     * 时间 t1..t9 递增（TIME_DESC 精确序 109..101）；「摄影」命中
     * 101/103/107 三行描述；「apple」命中 101/106（大小写不敏感）；
     * 价格单点 249900 唯一行 106。
     */
    @BeforeEach
    void setUpReplicaFixture() {
        fixtureCleanup();
        replicaJdbcTemplate.execute("""
                INSERT INTO shop (id, create_time, update_time, name, status) VALUES
                (99001, TIMESTAMP '2026-08-01 09:00:00', TIMESTAMP '2026-08-01 09:00:00', '搜索Fi店铺', 'NORMAL')""");
        replicaJdbcTemplate.execute("""
                INSERT INTO brand (id, create_time, update_time, name, status) VALUES
                (9901, TIMESTAMP '2026-08-01 08:00:00', TIMESTAMP '2026-08-01 08:00:00', '搜索Fi品牌1', 'ENABLED'),
                (9902, TIMESTAMP '2026-08-01 08:00:00', TIMESTAMP '2026-08-01 08:00:00', '搜索Fi品牌2', 'ENABLED'),
                (9903, TIMESTAMP '2026-08-01 08:00:00', TIMESTAMP '2026-08-01 08:00:00', '搜索Fi品牌3', 'ENABLED'),
                (9904, TIMESTAMP '2026-08-01 08:00:00', TIMESTAMP '2026-08-01 08:00:00', '搜索Fi品牌4', 'ENABLED')""");
        replicaJdbcTemplate.execute("""
                INSERT INTO product (id, name, description, status, shop_id, brand_id, category_id, tenant_id, create_time, update_time) VALUES
                (101, 'Apple iPhone 15 手机', '5G 旗舰摄影手机',    'ON_SALE', 99001, 9901, 101, '99001', TIMESTAMP '2026-08-01 10:00:00', TIMESTAMP '2026-08-01 10:00:00'),
                (102, '苹果平板 iPad 10',      '平板电脑',          'ON_SALE', 99001, 9901, 101, '99001', TIMESTAMP '2026-08-02 10:00:00', TIMESTAMP '2026-08-02 10:00:00'),
                (103, '小米手机 15',           '摄影爱好者的选择',   'ON_SALE', 99001, 9902, 101, '99001', TIMESTAMP '2026-08-03 10:00:00', TIMESTAMP '2026-08-03 10:00:00'),
                (104, '联想笔记本',             '轻薄办公本',        'ON_SALE', 99001, 9903, 102, '99001', TIMESTAMP '2026-08-04 10:00:00', TIMESTAMP '2026-08-04 10:00:00'),
                (105, '索尼降噪耳机',           '无线蓝牙耳机',      'ON_SALE', 99001, 9902, 103, '99001', TIMESTAMP '2026-08-05 10:00:00', TIMESTAMP '2026-08-05 10:00:00'),
                (106, 'Apple Watch 手表',      '智能手表',          'ON_SALE', 99001, 9901, 101, '99001', TIMESTAMP '2026-08-06 10:00:00', TIMESTAMP '2026-08-06 10:00:00'),
                (107, '华为智慧屏',             '家庭影院摄影画质',  'ON_SALE', 99001, 9904, 104, '99001', TIMESTAMP '2026-08-07 10:00:00', TIMESTAMP '2026-08-07 10:00:00'),
                (108, '联想平板 watch 伴侣',   '平板',              'ON_SALE', 99001, 9903, 102, '99001', TIMESTAMP '2026-08-08 10:00:00', TIMESTAMP '2026-08-08 10:00:00'),
                (109, '机械键盘',               '办公外设',          'ON_SALE', 99001, 9904, 103, '99001', TIMESTAMP '2026-08-09 10:00:00', TIMESTAMP '2026-08-09 10:00:00')""");
        replicaJdbcTemplate.execute("""
                INSERT INTO product_sku (id, product_id, enabled, price, spec_hash, spec_summary, tenant_id, create_time, update_time) VALUES
                (50101, 101, TRUE, 499900, 'h101', 'Fi黄色', '99001', TIMESTAMP '2026-08-01 10:00:00', TIMESTAMP '2026-08-01 10:00:00'),
                (50102, 102, TRUE, 299900, 'h102', 'Fi黑色', '99001', TIMESTAMP '2026-08-02 10:00:00', TIMESTAMP '2026-08-02 10:00:00'),
                (50103, 103, TRUE, 199900, 'h103', 'Fi白色', '99001', TIMESTAMP '2026-08-03 10:00:00', TIMESTAMP '2026-08-03 10:00:00'),
                (50104, 104, TRUE, 599900, 'h104', 'Fi银色', '99001', TIMESTAMP '2026-08-04 10:00:00', TIMESTAMP '2026-08-04 10:00:00'),
                (50105, 105, TRUE,  99900, 'h105', 'Fi白色', '99001', TIMESTAMP '2026-08-05 10:00:00', TIMESTAMP '2026-08-05 10:00:00'),
                (50106, 106, TRUE, 249900, 'h106', 'Fi黑色', '99001', TIMESTAMP '2026-08-06 10:00:00', TIMESTAMP '2026-08-06 10:00:00'),
                (50107, 107, TRUE, 399900, 'h107', 'Fi黑色', '99001', TIMESTAMP '2026-08-07 10:00:00', TIMESTAMP '2026-08-07 10:00:00'),
                (50108, 108, TRUE, 149900, 'h108', 'Fi蓝色', '99001', TIMESTAMP '2026-08-08 10:00:00', TIMESTAMP '2026-08-08 10:00:00'),
                (50109, 109, TRUE,  79900, 'h109', 'Fi白色', '99001', TIMESTAMP '2026-08-09 10:00:00', TIMESTAMP '2026-08-09 10:00:00')""");
        replicaJdbcTemplate.execute("""
                INSERT INTO inventory_item (id, sku_id, available, held, sold, version, tenant_id, create_time, update_time) VALUES
                (90201, 50101, 10,  0, 120,  0, '99001', TIMESTAMP '2026-08-01 10:00:00', TIMESTAMP '2026-08-01 10:00:00'),
                (90202, 50102, 20,  0, 300,  0, '99001', TIMESTAMP '2026-08-02 10:00:00', TIMESTAMP '2026-08-02 10:00:00'),
                (90203, 50103, 30,  0, 500,  0, '99001', TIMESTAMP '2026-08-03 10:00:00', TIMESTAMP '2026-08-03 10:00:00'),
                (90204, 50104, 40,  0,  80,  0, '99001', TIMESTAMP '2026-08-04 10:00:00', TIMESTAMP '2026-08-04 10:00:00'),
                (90205, 50105, 50,  0, 1000, 0, '99001', TIMESTAMP '2026-08-05 10:00:00', TIMESTAMP '2026-08-05 10:00:00'),
                (90206, 50106, 60,  0,   0,  0, '99001', TIMESTAMP '2026-08-06 10:00:00', TIMESTAMP '2026-08-06 10:00:00'),
                (90207, 50107, 70,  0,  60,  0, '99001', TIMESTAMP '2026-08-07 10:00:00', TIMESTAMP '2026-08-07 10:00:00'),
                (90208, 50108, 80,  0, 250,  0, '99001', TIMESTAMP '2026-08-08 10:00:00', TIMESTAMP '2026-08-08 10:00:00'),
                (90209, 50109, 90,  0, 900,  0, '99001', TIMESTAMP '2026-08-09 10:00:00', TIMESTAMP '2026-08-09 10:00:00')""");
        replicaJdbcTemplate.execute("""
                INSERT INTO product_image (id, product_id, url, is_primary, tenant_id, create_time, update_time) VALUES
                (80101, 101, '/files/101.jpg', TRUE, '99001', TIMESTAMP '2026-08-01 10:00:00', TIMESTAMP '2026-08-01 10:00:00'),
                (80102, 102, '/files/102.jpg', TRUE, '99001', TIMESTAMP '2026-08-02 10:00:00', TIMESTAMP '2026-08-02 10:00:00'),
                (80103, 103, '/files/103.jpg', TRUE, '99001', TIMESTAMP '2026-08-03 10:00:00', TIMESTAMP '2026-08-03 10:00:00'),
                (80104, 104, '/files/104.jpg', TRUE, '99001', TIMESTAMP '2026-08-04 10:00:00', TIMESTAMP '2026-08-04 10:00:00'),
                (80105, 105, '/files/105.jpg', TRUE, '99001', TIMESTAMP '2026-08-05 10:00:00', TIMESTAMP '2026-08-05 10:00:00'),
                (80106, 106, '/files/106.jpg', TRUE, '99001', TIMESTAMP '2026-08-06 10:00:00', TIMESTAMP '2026-08-06 10:00:00'),
                (80107, 107, '/files/107.jpg', TRUE, '99001', TIMESTAMP '2026-08-07 10:00:00', TIMESTAMP '2026-08-07 10:00:00'),
                (80108, 108, '/files/108.jpg', TRUE, '99001', TIMESTAMP '2026-08-08 10:00:00', TIMESTAMP '2026-08-08 10:00:00'),
                (80109, 109, '/files/109.jpg', TRUE, '99001', TIMESTAMP '2026-08-09 10:00:00', TIMESTAMP '2026-08-09 10:00:00')""");
    }

    /**
     * 用例后清理 fixture（镜像底层表逐表删）。删除顺序与插入无关（镜像表
     * 无外键），按子表→主表序书写保持可读；旧残留兜底幂等（同 id 段重删无害）。
     */
    @AfterEach
    void tearDownReplicaFixture() {
        fixtureCleanup();
    }

    /**
     * 幂等清理 fixture 段（@BeforeEach 前置 + @AfterEach 后置），库面恢复
     * 与 seed/其他测试数据零接触（只删专属 id 段）。
     */
    private void fixtureCleanup() {
        replicaJdbcTemplate.execute("""
                DELETE FROM inventory_item WHERE sku_id BETWEEN 50101 AND 50109""");
        replicaJdbcTemplate.execute("""
                DELETE FROM product_sku WHERE id BETWEEN 50101 AND 50109""");
        replicaJdbcTemplate.execute("""
                DELETE FROM product_image WHERE id BETWEEN 80101 AND 80109""");
        replicaJdbcTemplate.execute("""
                DELETE FROM product WHERE id BETWEEN 101 AND 109""");
        replicaJdbcTemplate.execute("""
                DELETE FROM brand WHERE id BETWEEN 9901 AND 9904""");
        replicaJdbcTemplate.execute("""
                DELETE FROM shop WHERE id = 99001""");
    }

    // ---------- happy path ----------

    /**
     * 关键词命中 + 大小写不敏感（upper() like 双库语义：「APPLE」命中标题
     * 含 Apple 的 101/106 两行；108 标题含小写 watch 不含 apple；B5.2）。
     */
    @Test
    @DisplayName("happy：关键词标题命中且大小写不敏感")
    void keywordHitTitleCaseInsensitive() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("APPLE", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));

        assertEquals(2, result.total());
        assertEquals(List.of(106L, 101L), productIds(result));
    }

    /**
     * 关键词描述命中（「摄影」命中 101/103/107 三行描述；B5.2 标题/描述匹配）。
     */
    @Test
    @DisplayName("happy：关键词描述命中")
    void keywordHitDescription() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("摄影", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));

        assertEquals(3, result.total());
        assertEquals(List.of(107L, 103L, 101L), productIds(result));
    }

    /**
     * 过滤组合生效（类目 101 + 品牌 9901 + 店铺 99001 + 价格 [400000, 600000]
     * 四过滤器同时命中仅 101；同条件品牌换 9902 → 空——组合为 AND 语义；B5.3）。
     */
    @Test
    @DisplayName("happy：类目+品牌+店铺+价格过滤组合生效")
    void filterCombination() {
        SearchCriteria criteria = new SearchCriteria(
                null, 101L, 9901L, FIXTURE_SHOP_ID, 400000L, 600000L, null);
        PageResult<ProductCard> result = productSearchService.search(criteria, new PageQuery(1, 10));

        assertEquals(1, result.total());
        assertEquals(List.of(101L), productIds(result));

        SearchCriteria noHitCriteria = new SearchCriteria(
                null, 101L, 9902L, FIXTURE_SHOP_ID, 400000L, 600000L, null);
        assertEquals(0, productSearchService.search(noHitCriteria, new PageQuery(1, 10)).total());
    }

    /**
     * 排序-销量降序（SALES_DESC 精确序：1000/900/500/300/250/120/80/60/0）。
     */
    @Test
    @DisplayName("happy：销量降序排序")
    void sortBySalesDesc() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, null, null, SearchSort.SALES_DESC),
                new PageQuery(1, 10));

        assertEquals(List.of(105L, 109L, 103L, 102L, 108L, 101L, 104L, 107L, 106L), productIds(result));
    }

    /**
     * 排序-价格升序（PRICE_ASC 精确序：79900/99900/149900/199900/249900/299900/399900/499900/599900）。
     */
    @Test
    @DisplayName("happy：价格升序排序")
    void sortByPriceAsc() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, null, null, SearchSort.PRICE_ASC),
                new PageQuery(1, 10));

        assertEquals(List.of(109L, 105L, 108L, 103L, 106L, 102L, 107L, 101L, 104L), productIds(result));
    }

    /**
     * 排序-上新时间降序（TIME_DESC 精确序 109..101；缺省排序=时间降序）。
     */
    @Test
    @DisplayName("happy：上新时间降序排序（默认排序）")
    void sortByTimeDesc() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));

        assertEquals(List.of(109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L), productIds(result));
    }

    // ---------- critical path ----------

    /**
     * happy：关键词与过滤组合生效（OR 组括号语义回归锁——关键词命中
     * 标题或描述任一大列，过滤条件与整个 OR 组 AND，而非仅与描述列
     * 组合）：关键词「手机」命中 name 101/103、desc 101；叠加类目 102
     * （fixture 内无可售手机行）恒空——括号缺失时 name 命中行不受类目
     * 约束会脏返回；空店铺叠加 → 恒空（AND 语义）。
     */
    @Test
    @DisplayName("happy：关键词与过滤组合生效（OR 组括号语义）")
    void keywordWithFilterCombination() {
        PageResult<ProductCard> noCategoryHit = productSearchService.search(
                new SearchCriteria("手机", 102L, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));
        assertEquals(0, noCategoryHit.total());

        PageResult<ProductCard> shopFiltered = productSearchService.search(
                new SearchCriteria("手机", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));
        assertEquals(2, shopFiltered.total());
        // 默认 TIME_DESC 排序：103（t3）在 101（t1）之前——断集合不考虑序
        assertTrue(productIds(shopFiltered).containsAll(List.of(101L, 103L)));

        PageResult<ProductCard> emptyShop = productSearchService.search(
                new SearchCriteria("手机", null, null, 99002L, null, null, null),
                new PageQuery(1, 10));
        assertEquals(0, emptyShop.total());
    }

    /**
     * 空结果：无命中关键词 → total 0 + 空列表（非 null），空态可提示（B5.2 空态）。
     */
    @Test
    @DisplayName("critical：无命中返回空结果")
    void noHitReturnsEmptyPage() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("不存在的商品词", null, null, FIXTURE_SHOP_ID, null, null, null),
                new PageQuery(1, 10));

        assertEquals(0, result.total());
        assertTrue(result.records().isEmpty());
        assertEquals(1, result.pageNum());
        assertEquals(10, result.pageSize());
    }

    /**
     * 分页边界：pageSize=4 共 9 行 → 页 3 余 1 行、页 4 越界空列表但
     * total 恒 9（total 与页无关）。
     */
    @Test
    @DisplayName("critical：分页末页与越界页")
    void paginationBoundary() {
        PageQuery page4 = new PageQuery(4, 4);
        PageResult<ProductCard> page1 = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, null, null, SearchSort.TIME_DESC),
                new PageQuery(1, 4));
        PageResult<ProductCard> page3 = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, null, null, SearchSort.TIME_DESC),
                new PageQuery(3, 4));
        PageResult<ProductCard> beyond = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, null, null, SearchSort.TIME_DESC),
                page4);

        assertEquals(9, page1.total());
        assertEquals(4, page1.records().size());
        assertEquals(1, page3.records().size());
        assertEquals(9, page3.total());
        assertEquals(0, beyond.records().size());
        assertEquals(9, beyond.total());
        assertEquals(4, beyond.pageNum());
    }

    /**
     * 价格边界-单点命中：minPrice == maxPrice 为闭区间单点（249900 唯一行 106）。
     */
    @Test
    @DisplayName("critical：价格单点闭区间命中")
    void priceSinglePointBoundary() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, 249900L, 249900L, null),
                new PageQuery(1, 10));

        assertEquals(1, result.total());
        assertEquals(List.of(106L), productIds(result));
    }

    /**
     * 价格边界-半开区间：仅 minPrice（≥500000 → 104 一行）。
     */
    @Test
    @DisplayName("critical：价格下界半开区间")
    void priceHalfOpenLowerBound() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria(null, null, null, FIXTURE_SHOP_ID, 500000L, null, null),
                new PageQuery(1, 10));

        assertEquals(1, result.total());
        assertEquals(List.of(104L), productIds(result));
    }

    // ---------- fail path ----------

    /**
     * 非法参数-负价格：minPrice 为负 → search.invalid_price_range 拒绝（400）。
     */
    @Test
    @DisplayName("error：负价格区间拒绝")
    void negativeMinPriceRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productSearchService.search(
                        new SearchCriteria(null, null, null, null, -1L, null, null),
                        new PageQuery(1, 10)));

        assertEquals("search.invalid_price_range", ex.getBusinessCode());
    }

    /**
     * 非法参数-区间倒挂：maxPrice < minPrice → search.invalid_price_range 拒绝（400）。
     */
    @Test
    @DisplayName("error：价格区间倒挂拒绝")
    void reversedPriceRangeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productSearchService.search(
                        new SearchCriteria(null, null, null, null, 200000L, 100000L, null),
                        new PageQuery(1, 10)));

        assertEquals("search.invalid_price_range", ex.getBusinessCode());
    }

    /**
     * 提取结果商品 ID 列表（断言载体）。
     *
     * @param result 搜索分页结果
     * @return 商品 ID 列表
     */
    private static List<Long> productIds(PageResult<ProductCard> result) {
        return result.records().stream().map(ProductCard::productId).toList();
    }
}