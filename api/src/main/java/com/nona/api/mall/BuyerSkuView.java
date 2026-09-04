package com.nona.api.mall;

/**
 * 买家商品详情-SKU 行（规格联动数据：选择后价格/库存随动）。
 * <p>
 * specHash/specSummary 与商品规格模板展开序对齐——前端按当前选中规格
 * 组合摘要定位 SKU；available=0 置灰不可选（无库存 SKU 置灰）。
 * 金额单位分。
 *
 * @param skuId       SKU ID
 * @param specHash    规格组合规范化摘要
 * @param specSummary 规格组合可读摘要
 * @param price       销售价（分）
 * @param available   可售量（买家可见库存；缺行/未初始化按 0）
 * @author nona9961
 */
public record BuyerSkuView(Long skuId, String specHash, String specSummary,
                           Long price, int available) {
}
