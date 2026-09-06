package com.nona.inf.persistence.repository;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import org.mybatis.dynamic.sql.ColumnAndConditionCriterion;
import org.mybatis.dynamic.sql.SortSpecification;
import org.mybatis.dynamic.sql.dsl.WhereDSL;
import org.mybatis.dynamic.sql.select.CountDSL;
import org.mybatis.dynamic.sql.select.QueryExpressionDSL;
import org.mybatis.dynamic.sql.select.SelectModel;
import org.mybatis.dynamic.sql.util.Buildable;
import org.mybatis.dynamic.sql.where.condition.IsLikeCaseInsensitiveWhenPresent;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.brandId;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.brandName;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.categoryId;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.coverImageUrl;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.description;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.minPrice;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.name;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.productId;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.productSearchView;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.salesTotal;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.shopId;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.shopName;
import static com.nona.inf.persistence.repository.replica.ProductSearchDynamicSqlSupport.updatedAt;
import static org.mybatis.dynamic.sql.SqlBuilder.countFrom;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualToWhenPresent;
import static org.mybatis.dynamic.sql.SqlBuilder.isGreaterThanOrEqualToWhenPresent;
import static org.mybatis.dynamic.sql.SqlBuilder.isLessThanOrEqualToWhenPresent;
import static org.mybatis.dynamic.sql.SqlBuilder.isLikeCaseInsensitiveWhenPresent;
import static org.mybatis.dynamic.sql.SqlBuilder.or;
import static org.mybatis.dynamic.sql.SqlBuilder.select;
import static org.mybatis.dynamic.sql.SqlBuilder.where;

/**
 * 商品搜索查询语句共享构建器（抽取：主库/replica 两通道同构查询的
 * 唯一构建位）。
 * <p>
 * 语义：product_search_view（主库实时视图与 PG 镜像视图同构同列）的
 * 条件拼装 + 白名单排序 + 分页切片 + 行映射统一由本类承载——窗口路由
 * 只换执行通道（{@link org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate}
 * 不同绑定），查询语义在两库间零漂移。构建形态与既有实现行为等价
 * （MyBatis Dynamic SQL 类型安全生成，无任何字符串拼接注入面；
 * 条件全部 when-present 形态，search 与 count 共用同一条件构建）。
 * 关键词通配符说明：库不提供 LIKE ESCAPE 子句，关键词中的
 * {@code % _} 按通配符语义参与匹配（调用方期望字面匹配时需自行转义）。
 *
 * @author nona9961
 */
public final class SearchViewQuerySupport {

    /**
     * 视图行 → 卡片映射（product_id/name/min_price/sales_total/shop_id/shop_name
     * 为非空列；cover_image_url/brand_name 可空，直接透传）。两通道共用
     * 同一行映射（列契约一致）。
     */
    public static final RowMapper<ProductCard> CARD_ROW_MAPPER = (rs, rowNum) -> new ProductCard(
            rs.getLong("product_id"),
            rs.getString("name"),
            rs.getString("cover_image_url"),
            rs.getLong("min_price"),
            rs.getLong("sales_total"),
            rs.getLong("shop_id"),
            rs.getString("shop_name"),
            rs.getString("brand_name"));

    /**
     * 工具类私有构造（纯静态构建位）。
     */
    private SearchViewQuerySupport() {
    }

    /**
     * 当前页查询语句构建（条件 + 白名单排序 + 分页切片）。
     *
     * @param criteria 检索条件（服务层已校验/归约）
     * @param page     分页请求（偏移量语义由 PageQuery.offset 承载）
     * @return 可渲染的分页查询语句（执行扩展器消费）
     */
    public static Buildable<SelectModel> pageStatement(SearchCriteria criteria, PageQuery page) {
        final QueryExpressionDSL<SelectModel> base = select(
                productId, name, coverImageUrl, minPrice, salesTotal, shopId, shopName, brandName)
                .from(productSearchView);
        final WhereDSL where = buildWhere(criteria);
        if (where != null) {
            return base.applyWhere(where.toWhereApplier())
                    .orderBy(orderSpec(criteria.sort()))
                    .limit(page.pageSize())
                    .offset(page.offset());
        }
        return base.orderBy(orderSpec(criteria.sort()))
                .limit(page.pageSize())
                .offset(page.offset());
    }

    /**
     * 总数统计语句构建（与 {@link #pageStatement} 同条件同口径）。
     *
     * @param criteria 检索条件（服务层已校验/归约）
     * @return 可渲染的 count 语句（执行扩展器消费）
     */
    public static CountDSL<SelectModel> countStatement(SearchCriteria criteria) {
        final CountDSL<SelectModel> countStatement = countFrom(productSearchView);
        final WhereDSL where = buildWhere(criteria);
        if (where != null) {
            countStatement.applyWhere(where.toWhereApplier());
        }
        return countStatement;
    }

    /**
     * 动态条件构建（search 与 count 共用，口径一致）。
     * <p>
     * 全部条件为 when-present 形态：keyword 经空白防御后按
     * {@code (name like :kw OR description like :kw)} 组命中——关键词
     * OR 组置于初始条件的子组（{@link ColumnAndConditionCriterion}），
     * 库渲染为括号包裹的 {@code (A or B)}，与后续 AND 过滤组合时优先级
     * 同手写 SQL 的 {@code (A OR B) AND ...}；等值/价格条件 null 即省略。
     * 无任何条件时返回 null——调用方跳过 where 子句（空条件按全表扫描
     * 语义处理）。
     *
     * @param criteria 检索条件（服务层已校验/归约）
     * @return 条件 DSL；无任何条件时返回 null
     */
    public static WhereDSL buildWhere(SearchCriteria criteria) {
        if (!hasConditions(criteria)) {
            return null;
        }
        final WhereDSL where = where(ColumnAndConditionCriterion.withColumn(name)
                .withCondition(keywordCondition(criteria.keyword()))
                .withSubCriteria(List.of(or(description, keywordCondition(criteria.keyword()))))
                .build());
        return where
                .and(categoryId, isEqualToWhenPresent(criteria.categoryId()))
                .and(brandId, isEqualToWhenPresent(criteria.brandId()))
                .and(shopId, isEqualToWhenPresent(criteria.shopId()))
                .and(minPrice, isGreaterThanOrEqualToWhenPresent(criteria.minPrice()))
                .and(minPrice, isLessThanOrEqualToWhenPresent(criteria.maxPrice()));
    }

    /**
     * 关键词匹配条件（标题/描述共用）：大小写不敏感 + 包含匹配 +
     * 空白防御。
     * <p>
     * 匹配形态：库渲染 {@code upper(col) like ?} 且参数经转换
     * （{@code %关键词%}，自动大写）——与 ILIKE 语义等价；空白/null
     * 关键词经 when-present + 过滤退化为空条件（渲染时省略）。
     *
     * @param keyword 关键词（服务层已 trim；此处再防御）
     * @return like 条件；空白/null 时为空条件
     */
    private static IsLikeCaseInsensitiveWhenPresent<String> keywordCondition(String keyword) {
        return isLikeCaseInsensitiveWhenPresent(keyword)
                .filter(SearchViewQuerySupport::hasText)
                .map(SearchViewQuerySupport::toContainsPattern);
    }

    /**
     * 检索条件是否非空（决定 where 子句是否需要构建）。
     *
     * @param criteria 检索条件
     * @return true = 至少一个条件生效
     */
    private static boolean hasConditions(SearchCriteria criteria) {
        return hasText(criteria.keyword())
                || criteria.categoryId() != null
                || criteria.brandId() != null
                || criteria.shopId() != null
                || criteria.minPrice() != null
                || criteria.maxPrice() != null;
    }

    /**
     * 白名单排序映射：枚举 → 静态列引用（方向语义内置在枚举 Javadoc；
     * 排序列不可由调用方指定，杜绝注入面）。价格升序为裸列引用
     * （库默认升序渲染），降序两轴经 {@code descending()} 修饰。
     *
     * @param sort 排序（null 视为默认 TIME_DESC）
     * @return 排序列引用（库渲染为 ORDER BY 子句）
     */
    private static SortSpecification orderSpec(SearchSort sort) {
        return switch (sort == null ? SearchSort.TIME_DESC : sort) {
            case SALES_DESC -> salesTotal.descending();
            case PRICE_ASC -> minPrice;
            case TIME_DESC -> updatedAt.descending();
        };
    }

    /**
     * 关键词有效判定（空白/null 均视为无关键词）。
     *
     * @param value 待判定文本
     * @return true = 非空白
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 关键词 → 包含匹配模式（两侧补 SQL 通配符，库侧自动转大写）。
     *
     * @param keyword 关键词（已保证非空白）
     * @return {@code %keyword%} 模式
     */
    private static String toContainsPattern(String keyword) {
        return "%" + keyword + "%";
    }
}