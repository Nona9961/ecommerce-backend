package com.nona.inf.persistence.repository.replica;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.repo.ProductSearchViewRepository;
import com.nona.inf.persistence.repository.SearchViewQuerySupport;
import org.mybatis.dynamic.sql.select.SelectModel;
import org.mybatis.dynamic.sql.util.Buildable;
import org.mybatis.dynamic.sql.util.spring.NamedParameterJdbcTemplateExtensions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 搜索读模型仓储实现（product_search_view 读取，replica 数据源专用）。
 * <p>
 * 实现形态：
 * <ul>
 *     <li>数据源：仅持有 replica 命名参数模板（{@code @Qualifier("replicaNamedParameterJdbcTemplate")}），
 *         静态装配白名单——本实现代码层面不触达主数据源，无动态路由；</li>
 *     <li>SQL：语句构建共用 {@link SearchViewQuerySupport}（抽取的共享
 *         构建器，主库/replica 两通道同构查询的唯一构建位——条件拼装/
 *         白名单排序/分页切片/行映射行为等价既有实现，由既有搜索集成
 *         回归用例锁行为）；</li>
 *     <li>执行：{@link NamedParameterJdbcTemplateExtensions} 统一渲染
 *         （SPRING_NAMED_PARAMETER + 命名参数绑定），不手写参数包装；</li>
 *     <li>视图语义依赖：在售/有货过滤与销量/价格聚合由 PG 视图
 *         product_search_view（外部 DDL）承载，本实现不重复实现。</li>
 * </ul>
 * 事务：纯读路径，不开写事务。
 *
 * @author nona9961
 */
@Repository
public class ProductSearchViewRepositoryImpl implements ProductSearchViewRepository {

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
        final Buildable<SelectModel> statement =
                SearchViewQuerySupport.pageStatement(criteria, page);
        return replicaExtensions.selectList(statement, SearchViewQuerySupport.CARD_ROW_MAPPER);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 与 {@link #search} 共用同一条件构建（共享构建器），
     * 保证 total 与当前页同口径；count(*) 由 countFrom 渲染。
     */
    @Override
    public long count(SearchCriteria criteria) {
        return replicaExtensions.count(SearchViewQuerySupport.countStatement(criteria));
    }
}