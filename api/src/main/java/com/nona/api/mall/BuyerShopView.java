package com.nona.api.mall;

/**
 * 买家商品详情-店铺卡片（店铺卡片展示：店名/进店入口）。
 *
 * @param shopId 店铺 ID
 * @param name   店铺名称
 * @param logo   logo URL（可空）
 * @author nona9961
 */
public record BuyerShopView(Long shopId, String name, String logo) {
}
