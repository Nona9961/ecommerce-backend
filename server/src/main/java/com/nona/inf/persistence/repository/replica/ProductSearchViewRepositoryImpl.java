package com.nona.inf.persistence.repository.replica;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.domain.search.repo.ProductSearchViewRepository;
import org.mybatis.dynamic.sql.ColumnAndConditionCriterion;
import org.mybatis.dynamic.sql.SortSpecification;
import org.mybatis.dynamic.sql.dsl.WhereDSL;
import org.mybatis.dynamic.sql.select.CountDSL;
import org.mybatis.dynamic.sql.select.QueryExpressionDSL;
import org.mybatis.dynamic.sql.select.SelectModel;
import org.mybatis.dynamic.sql.util.Buildable;
import org.mybatis.dynamic.sql.util.spring.NamedParameterJdbcTemplateExtensions;
import org.mybatis.dynamic.sql.where.condition.IsLikeCaseInsensitiveWhenPresent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
 * 搜索读模型仓储实现（product_search_view 读取，replica 数据源专用）。
 * <p>
 * 实现形态：
 * <ul>
 *     <li>数据源：仅持有 replica 命名参数模板（{@code @Qualifier("replicaNamedParameterJdbcTemplate")}），
 *         静态装配白名单——本实现代码层面不触达主数据源，无动态路由；</li>
 *     <li>SQL：MyBatis Dynamic SQL 类型安全生成（SQL 由库渲染，无任何
 *         字符串拼接注入面）——标题/描述大小写不敏感匹配（upper(col)
 *         like，等价 ILIKE 语义）+ 类目/品牌/店铺等值过滤 + 价格闭区间
 *         过滤（含端点，单侧为空即半开）+ 白名单排序（{@link SearchSort}
 *         → 静态列引用，排序列不可由调用方指定）+ limit/offset 分页；</li>
 *     <li>条件全部 when-present 形态（null 条件自动从 SQL 省略，
 *         关键词空白防御过滤）：search 与 count 共用同一条件构建
 *         （{@link #buildWhere}），total 与当前页同口径；关键词 OR 组
 *         以初始条件子组形态渲染（{@code (A or B)} 括号包裹，与 AND
 *         过滤组合时优先级同手写 SQL 的 {@code (A OR B) AND ...}）；</li>
 *     <li>执行：{@link NamedParameterJdbcTemplateExtensions} 统一渲染
 *         （SPRING_NAMED_PARAMETER + 命名参数绑定），不手写参数包装；</li>
 *     <li>视图语义依赖：在售/有货过滤与销量/价格聚合由 PG 视图
 *         product_search_view（部署位 DDL）承载，本实现不重复实现。</li>
 * </ul>
 * 关键词通配符说明：库不提供 LIKE ESCAPE 子句，关键词中的
 * {@code % _} 按通配符语义参与匹配（调用方期望字面匹配时需自行转义）。
 * 事务：纯读路径，不开写事务。
 *
 * @author nona9961
 */
@Repository
public class ProductSearchViewRepositoryImpl implements ProductSearchViewRepository {

    /**
     * 视图行 → 卡片映射（product_id/name/min_price/sales_total/shop_id/shop_name
     * 为非空列；cover_image_url/brand_name 可空，直接透传）。
     */
    private static final RowMapper<ProductCard> CARD_ROW_MAPPER = (rs, rowNum) -> new ProductCard(
            rs.getLong("product_id"),
            rs.getString("name"),
            rs.getString("cover_image_url"),
            rs.getLong("min_price"),
            rs.getLong("sales_total"),
            rs.getLong("shop_id"),
            rs.getString("shop_name"),
            rs.getString("brand_name"));

    /**
     * 搜索读模型扩展执行器（绑定 replica 命名参数模板；
     * 渲染策略 SPRING_NAMED_PARAMETER 由扩展类统一承担）。
     */
    private final NamedParameterJdbcTemplateExtensions replicaExtensions;

    /**
     * @param replicaNamedParameterJdbcTemplate replica 数据源命名参数模板
     */
    public ProductSearchViewRepositoryImpl(
            @Qualifier("replicaNamedParameterJdbcTemplate") NamedParameterJdbcTemplate replicaNamedParameterJdbcTemplate) {
        this.replicaExtensions = new NamedParameterJdbcTemplateExtensions(replicaNamedParameterJdbcTemplate);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 拼装语义：关键词（标题或描述大小写不敏感 like，when-present 条件族
     * + 空白防御，OR 两组括号包裹）+ 类目/品牌/店铺等值过滤 + 价格闭区间
     * （单侧为空则半开）→ 白名单排序 → limit/offset 参数化分页切片；
     * 条件由库渲染为命名参数，扩展执行器绑定执行。
     */
    @Override
    public List<ProductCard> search(SearchCriteria criteria, PageQuery page) {
        return replicaExtensions.selectList(pageStatement(criteria, page), CARD_ROW_MAPPER);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 与 {@link #search} 共用同一条件构建（{@link #buildWhere}），
     * 保证 total 与当前页同口径；count(*) 由 countFrom 渲染。
     */
    @Override
    public long count(SearchCriteria criteria) {
        final CountDSL<SelectModel> countStatement = countFrom(productSearchView);
        final WhereDSL where = buildWhere(criteria);
        if (where != null) {
            countStatement.applyWhere(where.toWhereApplier());
        }
        return replicaExtensions.count(countStatement);
    }

    /**
     * 当前页查询语句构建（条件 + 白名单排序 + 分页切片）。
     *
     * @param criteria 检索条件（服务层已校验/归约）
     * @param page     分页请求（偏移量语义由 PageQuery.offset 承载）
     * @return 可渲染的分页查询语句（扩展执行器消费）
     */
    private static Buildable<SelectModel> pageStatement(SearchCriteria criteria, PageQuery page) {
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
     * 动态条件构建（search 与 count 共用，口径一致）。
     * <p>
     * 全部条件为 when-present 形态：keyword 经空白防御后按
     * {@code (name like :kw OR description like :kw)} 组命中——关键词
     * OR 组置于初始条件的子组（{@link ColumnAndConditionCriterion}），
     * 库渲染为括号包裹的 {@code (A or B)}，与后续 AND 过滤组合时优先级
     * 同手写 SQL 的 {@code (A OR B) AND ...}（经 toWhereApplier 路径的
     * 渲染实测确认：子组形态保留括号）；等值/价格条件 null 即省略。
     * 无任何条件时返回 null——调用方跳过 where 子句（库对「全空 where」
     * 默认抛 NonRenderingWhereClauseException，空条件按全表扫描语义处理）。
     *
     * @param criteria 检索条件（服务层已校验/归约）
     * @return 条件 DSL；无任何条件时返回 null
     */
    private static WhereDSL buildWhere(SearchCriteria criteria) {
        if (!hasConditions(criteria)) {
            return null;
        }
        WhereDSL where = where(ColumnAndConditionCriterion.withColumn(name)
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
                .filter(ProductSearchViewRepositoryImpl::hasText)
                .map(ProductSearchViewRepositoryImpl::toContainsPattern);
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