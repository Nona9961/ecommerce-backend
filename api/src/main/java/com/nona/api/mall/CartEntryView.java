package com.nona.api.mall;

/**
 * 购物车条目视图（列表响应组成单元）：一个 SKU 一条。
 * <p>
 * 商品展示信息（名称/主图/价格）不在本视图承载——购物车列表只表达
 * 条目归属与数量形态，商品信息展示由买家端按需读详情/试算（结算金额
 * 明细属结算试算面）。
 *
 * @param skuId     SKU ID（条目定位键）
 * @param productId 商品 ID（条目创建时冗余，改量校验的读锚点）
 * @param quantity  数量（正数）
 * @param checked   结算勾选标记（仅勾选项进入结算）
 *
 * @author nona9961
 */
public record CartEntryView(
        Long skuId,
        Long productId,
        Integer quantity,
        Boolean checked
) {
}