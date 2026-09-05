package com.nona.inf.persistence.repository.replica;

import org.mybatis.dynamic.sql.AliasableSqlTable;
import org.mybatis.dynamic.sql.SqlColumn;

import java.sql.JDBCType;
import java.sql.Timestamp;

/**
 * product_search_view 表/列定义支撑（MyBatis Dynamic SQL 官方 support
 * 形态：final 类 + AliasableSqlTable 子类 + 静态双引用导出）。
 * <p>
 * 列集与视图实际用途对齐（按 SearchCriteria / ProductCard / SearchSort
 * 定）：等值过滤列（类目/品牌/店铺）、价格区间列、关键词匹配列（标题/
 * 描述）、卡片展示列（主图/店名/品牌名）、排序列（销量/价格/时间）。
 * JDBCType 与视图 DDL 列型对齐；未用到的视图列（available_total 等）
 * 不导出（需要时再增，只增不改）。
 *
 * @author nona9961
 */
public final class ProductSearchDynamicSqlSupport {

    /**
     * 视图表对象（无别名；查询侧不需要 join，别名能力由库的
     * AliasableSqlTable 基类保留，后续演进无需改表定义）。
     */
    public static final ProductSearchView productSearchView = new ProductSearchView();

    /**
     * 商品 ID（视图主键列，搜索结果标识）。
     */
    public static final SqlColumn<Long> productId = productSearchView.productId;

    /**
     * 商品名称（标题；关键词标题匹配列）。
     */
    public static final SqlColumn<String> name = productSearchView.name;

    /**
     * 商品描述（关键词描述匹配列）。
     */
    public static final SqlColumn<String> description = productSearchView.description;

    /**
     * 主图 URL（卡片展示，可空）。
     */
    public static final SqlColumn<String> coverImageUrl = productSearchView.coverImageUrl;

    /**
     * 在售 SKU 起步价（分；价格区间过滤/价格升序排序列）。
     */
    public static final SqlColumn<Long> minPrice = productSearchView.minPrice;

    /**
     * 销量（已售汇总；销量降序排序列）。
     */
    public static final SqlColumn<Long> salesTotal = productSearchView.salesTotal;

    /**
     * 店铺 ID（店铺过滤列）。
     */
    public static final SqlColumn<Long> shopId = productSearchView.shopId;

    /**
     * 店铺名称（卡片展示）。
     */
    public static final SqlColumn<String> shopName = productSearchView.shopName;

    /**
     * 品牌名称（卡片展示，可空）。
     */
    public static final SqlColumn<String> brandName = productSearchView.brandName;

    /**
     * 平台一级类目 ID（类目过滤列）。
     */
    public static final SqlColumn<Long> categoryId = productSearchView.categoryId;

    /**
     * 品牌 ID（品牌过滤列）。
     */
    public static final SqlColumn<Long> brandId = productSearchView.brandId;

    /**
     * 商品更新时间（上新时间降序排序列；仅排序引用，不做比较/绑定）。
     */
    public static final SqlColumn<Timestamp> updatedAt = productSearchView.updatedAt;

    /**
     * 工具类私有构造（纯静态支撑）。
     */
    private ProductSearchDynamicSqlSupport() {
    }

    /**
     * product_search_view 视图表定义（部署位 DDL 同名同列；
     * 列名/JDBCType 与视图定义对齐，H2 模拟表同形）。
     */
    public static final class ProductSearchView extends AliasableSqlTable<ProductSearchView> {

        /**
         * 商品 ID 列（BIGINT）。
         */
        public final SqlColumn<Long> productId = column("product_id", JDBCType.BIGINT);

        /**
         * 商品名称列（VARCHAR）。
         */
        public final SqlColumn<String> name = column("name", JDBCType.VARCHAR);

        /**
         * 商品描述列（VARCHAR，可空）。
         */
        public final SqlColumn<String> description = column("description", JDBCType.VARCHAR);

        /**
         * 主图 URL 列（VARCHAR，可空）。
         */
        public final SqlColumn<String> coverImageUrl = column("cover_image_url", JDBCType.VARCHAR);

        /**
         * 起步价列（BIGINT，分）。
         */
        public final SqlColumn<Long> minPrice = column("min_price", JDBCType.BIGINT);

        /**
         * 销量列（BIGINT）。
         */
        public final SqlColumn<Long> salesTotal = column("sales_total", JDBCType.BIGINT);

        /**
         * 店铺 ID 列（BIGINT）。
         */
        public final SqlColumn<Long> shopId = column("shop_id", JDBCType.BIGINT);

        /**
         * 店铺名称列（VARCHAR）。
         */
        public final SqlColumn<String> shopName = column("shop_name", JDBCType.VARCHAR);

        /**
         * 品牌名称列（VARCHAR，可空）。
         */
        public final SqlColumn<String> brandName = column("brand_name", JDBCType.VARCHAR);

        /**
         * 类目 ID 列（BIGINT）。
         */
        public final SqlColumn<Long> categoryId = column("category_id", JDBCType.BIGINT);

        /**
         * 品牌 ID 列（BIGINT）。
         */
        public final SqlColumn<Long> brandId = column("brand_id", JDBCType.BIGINT);

        /**
         * 更新时间列（TIMESTAMP；仅排序引用）。
         */
        public final SqlColumn<Timestamp> updatedAt = column("updated_at", JDBCType.TIMESTAMP);

        /**
         * 视图表构造（表名 = product_search_view，无 schema 前缀）。
         */
        public ProductSearchView() {
            super("product_search_view", ProductSearchView::new);
        }
    }
}