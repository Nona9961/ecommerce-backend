package com.nona.inf.persistence.repository.primary;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;
import com.nona.domain.search.repo.PrimaryProductSearchViewRepository;
import com.nona.inf.persistence.repository.SearchViewQuerySupport;
import org.mybatis.dynamic.sql.select.SelectModel;
import org.mybatis.dynamic.sql.util.Buildable;
import org.mybatis.dynamic.sql.util.spring.NamedParameterJdbcTemplateExtensions;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 搜索主库通道实现（写后窗口命中时的强一致查询，主库覆盖）。
 * <p>
 * 实现契约：
 * <ul>
 *     <li>数据源：仅持有无限定 {@link NamedParameterJdbcTemplate}——
 *         经 {@code ReplicaDataSourceConfig} 回填的 {@code @Primary}
 *         主库模板（双模板回填纪律：主库 @Primary / replica 限名隔离），
 *         代码层面不触达 replica 数据源；</li>
 *     <li>SQL：与 replica 通道同一共享构建器
 *         {@link SearchViewQuerySupport}（同构查询，语句构建唯一位）——
 *         窗口路由只换执行通道，查询语义零漂移；查询物 = 主库侧
 *         {@code product_search_view}（部署位 DDL 基于源表的实时视图，
 *         与 PG 镜像视图同构同规则——视图 DDL 归属部署位、不入业务仓库
 *         （既有先例）；CDC 未同步的新写内容经主库通道立即可见
 *         （read-your-writes）；</li>
 *     <li>行映射：与 replica 通道同一 {@code CARD_ROW_MAPPER}（列契约
 *         product_id/name/cover_image_url/min_price/sales_total/shop_id/
 *         shop_name/brand_name 一致）；</li>
 *     <li>执行：{@link NamedParameterJdbcTemplateExtensions} 统一渲染
 *         （SPRING_NAMED_PARAMETER + 命名参数绑定），与 replica 同款执行
 *         形态；</li>
 *     <li>事务：纯读路径，不开写事务。</li>
 * </ul>
 * 路由决策不在本类：搜索入口服务按窗口命中选择本通道或 replica 通道。
 *
 * @author nona9961
 */
@Repository
public class PrimaryProductSearchViewRepositoryImpl implements PrimaryProductSearchViewRepository {

    /**
     * 搜索主库扩展执行器（绑定主库命名参数模板；
     * 渲染策略 SPRING_NAMED_PARAMETER 由扩展类统一承担）。
     */
    private final NamedParameterJdbcTemplateExtensions primaryExtensions;

    /**
     * @param primaryNamedParameterJdbcTemplate 主库命名参数模板（{@code @Primary}
     *                                          回填 bean，无限定注入命中主库）
     */
    public PrimaryProductSearchViewRepositoryImpl(
            NamedParameterJdbcTemplate primaryNamedParameterJdbcTemplate) {
        this.primaryExtensions =
                new NamedParameterJdbcTemplateExtensions(primaryNamedParameterJdbcTemplate);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 执行形态：与 replica 通道同一共享构建器 + 同一行映射，仅执行模板
     * 换为 @Primary 主库模板（实时视图承载 CDC 未同步的新写内容）。
     */
    @Override
    public List<ProductCard> search(SearchCriteria criteria, PageQuery page) {
        final Buildable<SelectModel> statement =
                SearchViewQuerySupport.pageStatement(criteria, page);
        return primaryExtensions.selectList(statement, SearchViewQuerySupport.CARD_ROW_MAPPER);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 与 {@link #search} 共用同一条件构建（共享构建器），
     * 保证 total 与当前页同口径。
     */
    @Override
    public long count(SearchCriteria criteria) {
        return primaryExtensions.count(SearchViewQuerySupport.countStatement(criteria));
    }
}