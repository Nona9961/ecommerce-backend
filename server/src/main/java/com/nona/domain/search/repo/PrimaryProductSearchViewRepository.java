package com.nona.domain.search.repo;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;

import java.util.List;

/**
 * 搜索主库通道仓储（写后窗口命中时的强一致搜索查询，主库覆盖）。
 * <p>
 * 与 {@link ProductSearchViewRepository}（replica 通道）的协作语义：
 * <ul>
 *     <li><b>同构查询</b>：同一 SQL 构建（共享 support 类/statement
 *         构建，行为等价 replica SQL）——窗口路由只换
 *         执行通道，查询语义零漂移；</li>
 *     <li><b>查询物</b>：主库侧 {@code product_search_view}（部署位
 *         DDL 基于源表的实时视图，与 PG 镜像视图同构同规则——视图 DDL
 *         归属部署位、不入业务仓库（既有先例）；CDC 未同步的新写
 *         内容经主库通道立即可见（read-your-writes）；</li>
 *     <li><b>静态绑定</b>：本实现仅持有主库 NamedParameterJdbcTemplate
 *         （{@code @Primary} 回填 bean，无运行时路由）——「静态装配
 *         白名单 + 账号级受控覆盖」纪律与 replica 通道一致；</li>
 *     <li><b>事务</b>：纯读路径，不开写事务。</li>
 * </ul>
 * 路由决策只出现在搜索入口服务（窗口命中 → 本通道；否则 → replica
 * 通道），本接口不感知任何路由。
 *
 * @author nona9961
 */
public interface PrimaryProductSearchViewRepository {

    /**
     * 按条件查询当前页卡片（主库实时视图，同构语义见
     * {@link ProductSearchViewRepository#search}）。
     *
     * @param criteria 检索条件（合法性由服务层校验位保证）
     * @param page     分页请求（偏移量语义由 PageQuery.offset 承载）
     * @return 当前页卡片列表；无命中返回空列表（非 null）
     */
    List<ProductCard> search(SearchCriteria criteria, PageQuery page);

    /**
     * 按条件统计命中总数（与 {@link #search} 同条件同口径）。
     *
     * @param criteria 检索条件
     * @return 命中商品数
     */
    long count(SearchCriteria criteria);
}