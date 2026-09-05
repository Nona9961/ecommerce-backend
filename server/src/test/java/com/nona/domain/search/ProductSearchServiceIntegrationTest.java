package com.nona.domain.search;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.ProductSearchService;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.exceptions.BusinessException;
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
 * 商品搜索服务集成测试（WU-41 一期：PG 读库 + product_search_view 查询服务）。
 * <p>
 * 测试策略（决策 a）：replica 数据源以 H2 内存库模拟（MODE=PostgreSQL；
 * ILIKE 语法 H2 2.4.240 原生支持，实测通过）——fixture 直接构建
 * product_search_view 模拟表（承载「视图已聚合：在售 + 有货 + 价格/销量
 * 聚合」的行集成果），服务层条件拼装/过滤/排序/分页语义全链路跑通；
 * 真实 PG 视图 DDL 的聚合正确性由部署位（verify.sh + 演练）兜底。
 * <p>
 * 场景三分：
 * <ul>
 *     <li>happy：关键词命中（大小写不敏感/描述命中）、过滤组合
 *         （类目+品牌+店铺+价格）、排序三轴（销量/价格/时间）精确序；</li>
 *     <li>critical：空结果、分页边界（末页/越界页）、价格单点与半开区间；</li>
 *     <li>error：非法价格区间（负值/倒挂）按
 *         {@code search.invalid_price_range} 400 拒绝。</li>
 * </ul>
 * <p>
 * 红阶段（WU-41 红）：本测试全红，失败原因 = 服务/仓储实现缺失
 * （UnsupportedOperationException），非语法或装配错误。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class ProductSearchServiceIntegrationTest {

    /**
     * 被测搜索服务
     */
    @Autowired
    private ProductSearchService productSearchService;

    /**
     * replica 数据源 JdbcTemplate（fixture 直连，模拟 PG 视图行集）
     */
    @Autowired
    @Qualifier("replicaJdbcTemplate")
    private JdbcTemplate replicaJdbcTemplate;

    /**
     * 重建 product_search_view 模拟表并填充 fixture 行。
     * <p>
     * Fixture 布局（9 行 = 关键词矩阵 × 过滤维度 × 排序三轴可断言）：
     * <pre>
     * id  name                     desc             price    sales  shop  brand  cat   time
     * 101 Apple iPhone 15 手机     5G 旗舰摄影手机   499900   120    A9001 B1 501 C1 101  t1
     * 102 苹果平板 iPad 10         平板电脑           299900   300    A9001 B1 501 C1 101  t2
     * 103 小米手机 15              摄影爱好者的选择   199900   500    B9002 B2 502 C1 101  t3
     * 104 联想笔记本               轻薄办公本         599900    80    B9002 B3 503 C2 102  t4
     * 105 索尼降噪耳机             无线蓝牙耳机        99900  1000    C9003 B2 502 C3 103  t5
     * 106 Apple Watch 手表         智能手表           249900     0    A9001 B1 501 C1 101  t6
     * 107 华为智慧屏               家庭影院摄影画质   399900    60    C9003 B4 504 C4 104  t7
     * 108 联想平板 watch 伴侣      平板               149900   250    B9002 B3 503 C2 102  t8
     * 109 机械键盘                 办公外设            79900   900    C9003 B4 504 C3 103  t9
     * </pre>
     * 时间 t1..t9 递增（TIME_DESC 精确序 109..101）；「摄影」命中
     * 101/103/107 三行描述；「apple」命中 101/106（大小写不敏感）；
     * 价格单点 249900 唯一行 106。
     */
    @BeforeEach
    void setUpReplicaView() {
        replicaJdbcTemplate.execute("DROP TABLE IF EXISTS product_search_view");
        replicaJdbcTemplate.execute("""
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
        replicaJdbcTemplate.execute("""
                INSERT INTO product_search_view VALUES
                (101, 'Apple iPhone 15 手机',   '5G 旗舰摄影手机',  '/files/101.jpg', 499900, 120,  10, 9001, '店铺A', 501, '品牌1', 101, TIMESTAMP '2026-08-01 10:00:00'),
                (102, '苹果平板 iPad 10',        '平板电脑',          '/files/102.jpg', 299900, 300,  20, 9001, '店铺A', 501, '品牌1', 101, TIMESTAMP '2026-08-02 10:00:00'),
                (103, '小米手机 15',            '摄影爱好者的选择',   '/files/103.jpg', 199900, 500,  30, 9002, '店铺B', 502, '品牌2', 101, TIMESTAMP '2026-08-03 10:00:00'),
                (104, '联想笔记本',              '轻薄办公本',        '/files/104.jpg', 599900, 80,   40, 9002, '店铺B', 503, '品牌3', 102, TIMESTAMP '2026-08-04 10:00:00'),
                (105, '索尼降噪耳机',            '无线蓝牙耳机',      '/files/105.jpg',  99900, 1000, 50, 9003, '店铺C', 502, '品牌2', 103, TIMESTAMP '2026-08-05 10:00:00'),
                (106, 'Apple Watch 手表',       '智能手表',          '/files/106.jpg', 249900, 0,    60, 9001, '店铺A', 501, '品牌1', 101, TIMESTAMP '2026-08-06 10:00:00'),
                (107, '华为智慧屏',              '家庭影院摄影画质',  '/files/107.jpg', 399900, 60,   70, 9003, '店铺C', 504, '品牌4', 104, TIMESTAMP '2026-08-07 10:00:00'),
                (108, '联想平板 watch 伴侣',    '平板',              '/files/108.jpg', 149900, 250,  80, 9002, '店铺B', 503, '品牌3', 102, TIMESTAMP '2026-08-08 10:00:00'),
                (109, '机械键盘',                '办公外设',          '/files/109.jpg',  79900, 900,  90, 9003, '店铺C', 504, '品牌4', 103, TIMESTAMP '2026-08-09 10:00:00')
                """);
    }

    // ---------- happy path ----------

    /**
     * 关键词命中 + 大小写不敏感（ILIKE：「APPLE」命中标题含 Apple 的
     * 101/106 两行；108 标题含小写 watch 不含 apple，不命中；B5.2）。
     */
    @Test
    @DisplayName("happy：关键词标题命中且大小写不敏感")
    void keywordHitTitleCaseInsensitive() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("APPLE", null, null, null, null, null, null),
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
                new SearchCriteria("摄影", null, null, null, null, null, null),
                new PageQuery(1, 10));

        assertEquals(3, result.total());
        assertEquals(List.of(107L, 103L, 101L), productIds(result));
    }

    /**
     * 过滤组合生效（类目 101 + 品牌 501 + 店铺 9001 + 价格 [400000, 600000]
     * 四过滤器同时命中仅 101；同条件品牌换 502 → 空——组合为 AND 语义；B5.3）。
     */
    @Test
    @DisplayName("happy：类目+品牌+店铺+价格过滤组合生效")
    void filterCombination() {
        SearchCriteria criteria = new SearchCriteria(
                null, 101L, 501L, 9001L, 400000L, 600000L, null);
        PageResult<ProductCard> result = productSearchService.search(criteria, new PageQuery(1, 10));

        assertEquals(1, result.total());
        assertEquals(List.of(101L), productIds(result));

        SearchCriteria noHitCriteria = new SearchCriteria(
                null, 101L, 502L, 9001L, 400000L, 600000L, null);
        assertEquals(0, productSearchService.search(noHitCriteria, new PageQuery(1, 10)).total());
    }

    /**
     * 排序-销量降序（SALES_DESC 精确序：1000/900/500/300/250/120/80/60/0）。
     */
    @Test
    @DisplayName("happy：销量降序排序")
    void sortBySalesDesc() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria(null, null, null, null, null, null, SearchSort.SALES_DESC),
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
                new SearchCriteria(null, null, null, null, null, null, SearchSort.PRICE_ASC),
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
                new SearchCriteria(null, null, null, null, null, null, null),
                new PageQuery(1, 10));

        assertEquals(List.of(109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L), productIds(result));
    }

    // ---------- critical path ----------

    /**
     * happy：关键词与过滤组合生效（OR 组括号语义回归锁——关键词命中
     * 标题或描述任一大列，过滤条件与整个 OR 组 AND，而非仅与描述列
     * 组合）：关键词「手机」命中 name 101/103、desc 101；叠加类目 102
     * （无可售手机行）恒空——括号缺失时 name 命中行不受类目约束会脏
     * 返回；叠加店铺 9001 时仅 desc 命中的 101 保留（103 属店铺 B）。
     */
    @Test
    @DisplayName("happy：关键词与过滤组合生效（OR 组括号语义）")
    void keywordWithFilterCombination() {
        PageResult<ProductCard> noCategoryHit = productSearchService.search(
                new SearchCriteria("手机", 102L, null, null, null, null, null),
                new PageQuery(1, 10));
        assertEquals(0, noCategoryHit.total());

        PageResult<ProductCard> shopFiltered = productSearchService.search(
                new SearchCriteria("手机", null, null, 9001L, null, null, null),
                new PageQuery(1, 10));
        assertEquals(1, shopFiltered.total());
        assertEquals(List.of(101L), productIds(shopFiltered));
    }

    /**
     * 空结果：无命中关键词 → total 0 + 空列表（非 null），空态可提示（B5.2 空态）。
     */
    @Test
    @DisplayName("critical：无命中返回空结果")
    void noHitReturnsEmptyPage() {
        PageResult<ProductCard> result = productSearchService.search(
                new SearchCriteria("不存在的商品词", null, null, null, null, null, null),
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
                new SearchCriteria(null, null, null, null, null, null, SearchSort.TIME_DESC),
                new PageQuery(1, 4));
        PageResult<ProductCard> page3 = productSearchService.search(
                new SearchCriteria(null, null, null, null, null, null, SearchSort.TIME_DESC),
                new PageQuery(3, 4));
        PageResult<ProductCard> beyond = productSearchService.search(
                new SearchCriteria(null, null, null, null, null, null, SearchSort.TIME_DESC),
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
                new SearchCriteria(null, null, null, null, 249900L, 249900L, null),
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
                new SearchCriteria(null, null, null, null, 500000L, null, null),
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