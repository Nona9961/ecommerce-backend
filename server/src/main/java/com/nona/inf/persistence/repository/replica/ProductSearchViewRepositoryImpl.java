package com.nona.inf.persistence.repository.replica;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.ports.SearchSort;
import com.nona.domain.search.repo.ProductSearchViewRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索读模型仓储实现（product_search_view 读取，replica 数据源专用）。
 * <p>
 * 实现形态：
 * <ul>
 *     <li>数据源：仅持有 replica JdbcTemplate（{@code @Qualifier("replicaJdbcTemplate")}），
 *         静态装配白名单——本实现代码层面不触达主数据源，无动态路由；</li>
 *     <li>SQL：标题/描述 ILIKE（一期不分词，TD-16）+ 类目/品牌/店铺等值
 *         过滤 + 价格闭区间过滤 + 白名单排序（{@link SearchSort} → 固定
 *         列映射，杜绝列名注入）+ LIMIT/OFFSET 分页；所有条件值经
 *         {@code ?} 占位参数绑定，无字符串拼接注入面；</li>
 *     <li>关键词转义：{@code % _ \\} 通配符按字面匹配转义
 *         （ESCAPE 子句），用户输入不具通配符语义；</li>
 *     <li>视图语义依赖：在售/有货过滤与销量/价格聚合由 PG 视图
 *         product_search_view（部署位 DDL）承载，本实现不重复实现。</li>
 * </ul>
 * 事务：纯读路径，不开写事务。
 *
 * @author nona9961
 */
@Repository
public class ProductSearchViewRepositoryImpl implements ProductSearchViewRepository {

    /**
     * 视图查询列（ProductCard 冻结字段集，与部署位 DDL 列名对齐）。
     */
    private static final String BASE_SELECT = "SELECT product_id, name, cover_image_url, "
            + "min_price, sales_total, shop_id, shop_name, brand_name FROM product_search_view";

    /**
     * 视图总数统计基句（与 {@link #BASE_SELECT} 同视图同条件）。
     */
    private static final String BASE_COUNT = "SELECT COUNT(*) FROM product_search_view";

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
     * replica 数据源 JdbcTemplate（PG 读库；@Qualifier 显式限定，
     * 与主库数据源零关联）。
     */
    private final JdbcTemplate replicaJdbcTemplate;

    /**
     * @param replicaJdbcTemplate replica 数据源 JdbcTemplate
     */
    public ProductSearchViewRepositoryImpl(
            @Qualifier("replicaJdbcTemplate") JdbcTemplate replicaJdbcTemplate) {
        this.replicaJdbcTemplate = replicaJdbcTemplate;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 拼装语义：关键词（标题或描述 ILIKE，含通配符字面转义）+ 类目/品牌/
     * 店铺等值过滤（均有值时 AND 组合）+ 价格闭区间（单侧为空则半开）→
     * 白名单排序 → LIMIT/OFFSET 参数化分页切片。
     */
    @Override
    public List<ProductCard> search(SearchCriteria criteria, PageQuery page) {
        final List<Object> args = new ArrayList<>();
        final String sql = BASE_SELECT
                + buildWhere(criteria, args)
                + orderByClause(criteria.sort())
                + " LIMIT ? OFFSET ?";
        args.add(page.pageSize());
        args.add(page.offset());
        return replicaJdbcTemplate.query(sql, CARD_ROW_MAPPER, args.toArray());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 与 {@link #search} 共用同一条件拼装（{@link #buildWhere}），
     * 保证 total 与当前页同口径。
     */
    @Override
    public long count(SearchCriteria criteria) {
        final List<Object> args = new ArrayList<>();
        final String sql = BASE_COUNT + buildWhere(criteria, args);
        final Long total = replicaJdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 条件子句拼装（全部参数化：条件片段为编译期常量，值经占位符绑定）。
     *
     * @param criteria 检索条件（服务层已校验/归约）
     * @param args     参数收集器（按片段出现顺序追加绑定值）
     * @return WHERE 子句；无任何条件时返回空串
     */
    private static String buildWhere(SearchCriteria criteria, List<Object> args) {
        final List<String> conditions = new ArrayList<>(6);
        if (criteria.keyword() != null) {
            conditions.add("(name ILIKE ? ESCAPE '\\' OR description ILIKE ? ESCAPE '\\')");
            final String pattern = "%" + escapeLike(criteria.keyword()) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (criteria.categoryId() != null) {
            conditions.add("category_id = ?");
            args.add(criteria.categoryId());
        }
        if (criteria.brandId() != null) {
            conditions.add("brand_id = ?");
            args.add(criteria.brandId());
        }
        if (criteria.shopId() != null) {
            conditions.add("shop_id = ?");
            args.add(criteria.shopId());
        }
        if (criteria.minPrice() != null) {
            conditions.add("min_price >= ?");
            args.add(criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            conditions.add("min_price <= ?");
            args.add(criteria.maxPrice());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * 白名单排序子句：枚举 → 固定列映射（方向语义内置在枚举 Javadoc）；
     * 排序列不可由调用方指定，杜绝列名注入。
     *
     * @param sort 排序（null 视为默认 TIME_DESC）
     * @return ORDER BY 子句
     */
    private static String orderByClause(SearchSort sort) {
        return switch (sort == null ? SearchSort.TIME_DESC : sort) {
            case SALES_DESC -> " ORDER BY sales_total DESC";
            case PRICE_ASC -> " ORDER BY min_price ASC";
            case TIME_DESC -> " ORDER BY updated_at DESC";
        };
    }

    /**
     * LIKE 通配符字面转义：{@code \\ % _} 前置反斜杠（配合 ESCAPE 子句）。
     *
     * @param raw 用户关键词（已脱空白）
     * @return 转义后的匹配片段
     */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}