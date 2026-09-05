package com.nona.domain.search.ports;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 商品搜索服务（search 域查询契约，纯读意图，引擎无关）。
 * <p>
 * 语义约束：
 * <ul>
 *     <li><b>数据源</b>：读 PG 镜像视图 product_search_view（CDC 最终
 *         一致，秒级延迟可接受——TD-08）；本服务不触达主库，无写路径；</li>
 *     <li><b>结果范围</b>：视图侧已过滤在售（ON_SALE）+ 有货（可售汇总
 *         &gt; 0）商品，调用方无需重复判活（下架/审核中/无货不出现在
 *         搜索结果，B5 语义）；</li>
 *     <li><b>一期匹配</b>：标题/描述 ILIKE 关键词（大小写不敏感）+
 *         分类/价格区间/品牌/店铺过滤 + 销量/价格/时间三轴排序（TD-16；
 *         II 期 tsvector 换实现不换签名）；</li>
 *     <li><b>租户语义</b>：平台级全局命中（买家视角，无店铺归属过滤），
 *         PG 侧无 Hibernate 租户过滤（TD-12 放行形态）；</li>
 *     <li><b>事务</b>：读路径不开写事务。</li>
 * </ul>
 * 接口签名契约冻结：II 期更换实现（tsvector/外部引擎）不影响调用方。
 *
 * @author nona9961
 */
public interface ProductSearchService {

    /**
     * 商品搜索（分页）。
     *
     * @param criteria 检索条件（关键词/类目/品牌/价格区间/店铺/排序；
     *                 非法价格区间按 {@code search.invalid_price_range}
     *                 400 拒绝）
     * @param page     分页请求（pageNum/pageSize 归一化：第 1 页起、
     *                 默认 10、上限 100）
     * @return 分页卡片结果（空搜索返回空列表 + total 0，非 null）
     */
    PageResult<ProductCard> search(SearchCriteria criteria, PageQuery page);
}