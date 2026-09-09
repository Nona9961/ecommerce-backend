package com.nona.domain.catalog.repo;

import java.util.Collection;
import java.util.List;

/**
 * SKU 商品摘要投影仓储（catalog 域只读投影契约，商家库存列表/调整
 * 回显的 catalog join 面，WU-60 冻结；实现在基础设施层）。
 * <p>
 * 语义约束：
 * <ul>
 *     <li>批量按 SKU ID 集合装配商品摘要（单查询投影，非逐个聚合
 *         装载——库存分页页面至多百行、逐商品 getByID 装载为 N+1，
 *         投影口径一次性收敛）；</li>
 *     <li>读经租户过滤（fail-closed：跨店铺 SKU 不命中——与库存行
 *         查询同店铺口径，双域同店校验）；</li>
 *     <li>无命中返回空列表（fail-safe，不抛异常）；调用方对「库存行
 *         存在但投影缺失」按数据异常呈现（fail-closed——两侧表驱动
 *         生命周期不同步属装配数据异常，不静默降级为 null 展示）；</li>
 *     <li>读取面纪律：投影行未登记变更追踪（只读呈现，无变更路径）。</li>
 * </ul>
 * <p>
 * 参数守卫（fail-closed）：{@code skuIds} 为 null 或空集合返回空列表
 * （无装配对象，非调用错误）。
 *
 * @author nona9961
 */
public interface SkuProductViewRepository {

    /**
     * 按 SKU ID 集合批量装配商品摘要。
     *
     * @param skuIds SKU ID 集合（null/空 = 空列表）
     * @return SKU 摘要列表（顺序不承诺——调用方按需重排）；无命中为空列表
     */
    List<SkuProductView> listBySkuIds(Collection<Long> skuIds);
}