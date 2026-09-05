package com.nona.domain.search.repo;

import com.nona.api.common.PageQuery;
import com.nona.domain.search.ports.ProductCard;
import com.nona.domain.search.ports.SearchCriteria;

import java.util.List;

/**
 * 搜索读模型仓储（product_search_view 读取契约，纯读）。
 * <p>
 * 只读 PG replica 数据源（静态装配白名单原则：本仓储实现仅持有
 * replica JdbcTemplate，无运行时路由，杜绝误读主库或误写路径）；
 * 视图已承载聚合语义（在售 + 有货过滤、销量/价格聚合），本仓储负责
 * 条件拼装 + 白名单排序 + 分页切片。
 *
 * @author nona9961
 */
public interface ProductSearchViewRepository {

    /**
     * 按条件查询当前页卡片。
     *
     * @param criteria 检索条件（合法性由服务层校验位保证）
     * @param page     分页请求（偏移量语义由 PageQuery.offset 承载）
     * @return 当前页卡片列表；无命中返回空列表（非 null）
     */
    List<ProductCard> search(SearchCriteria criteria, PageQuery page);

    /**
     * 按条件统计命中总数（分页 total 用；与 {@link #search} 同条件）。
     *
     * @param criteria 检索条件
     * @return 命中商品数
     */
    long count(SearchCriteria criteria);
}