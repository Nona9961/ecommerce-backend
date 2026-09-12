package com.nona.domain.catalog.ports;

/**
 * 商品查询门面（catalog 跨上下文只读契约，字段形态冻结）：买家详情读
 * （主库强一致）+ 下单前置读复用（order 下单编排、search 写后窗口
 * 主库覆盖共用）的唯一查询入口。
 * <p>
 * 语义约束：
 * <ul>
 *     <li><b>在售校验</b>：仅 ON_SALE 商品可读——不存在/草稿/待审核/已
 *         下架统一按不存在呈现（{@code CATALOG_PRODUCT_NOT_FOUND} 404，
 *         不泄露生命周期状态；下架后买家不可见）。下单前置与
 *         详情读共享同一语义（非在售即不可购）；下单编排需要区分「已
 *         下架」提示时由订单域另行读生命周期（契约演进只增不改）；</li>
 *     <li><b>精确价与库存</b>：SKU 级价格（product_sku 生效内容）+ 可售量
 *         （经库存域 {@code InventoryFacade.queryAvailable} 跨上下文读——
 *         未初始化/缺行按 0，zip 语义）；</li>
 *     <li><b>运费模板/店铺信息</b>：运费模板规则概要（<b>恒非空</b>——商品绑定
 *         模板概要；未绑定/绑定悬挂回退店铺默认模板概要，非空设计，
 *         不存在 null 语义）+ 店铺卡片信息（买家详情展示用）；</li>
 *     <li><b>租户放行形态</b>：本契约读 tenant 表（product/sku/freight），
 *         买家视角（contextTenant 空）须在读放行上下文内调用——放行
 *         （@CrossTenant）只出现在应用层用例方法，由调用方（mall 详情
 *         用例/order 编排）负责，本接口实现不放行；</li>
 *     <li>事务：读路径不开写事务；调用方事务内随 REQUIRED 并入。</li>
 * </ul>
 * 视图承载生效内容（审核通过的正式内容——待审草稿买家不可见）。
 *
 * @author nona9961
 */
public interface ProductQueryFacade {

    /**
     * 买家商品视图（详情页全量 + 下单前置信息）。
     *
     * @param productId 商品 ID
     * @return 买家视图（商品必为 ON_SALE）
     */
    ProductBuyerView getBuyerView(Long productId);
}
