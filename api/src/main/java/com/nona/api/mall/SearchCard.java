package com.nona.api.mall;

/**
 * 搜索结果卡片（搜索列表项视图，WU-59 回注——形状钉死前端
 * searchApi.ts {@code SearchCardWire} 8 字段逐名对应，金额折分为元
 * 下行；与 search 域 ProductCard 字段原样对齐）。
 * <p>
 * 金额纪律：minPrice 为分（在售 SKU 起步价）；salesTotal 为销量汇总
 * （粗粒度热度，非精确口径）；coverImageUrl / brandName 可空防御形态。
 *
 * @param productId     商品 ID
 * @param name          商品名称（标题）
 * @param coverImageUrl 主图 URL（可空防御形态）
 * @param minPrice      在售 SKU 最低售价（分）
 * @param salesTotal    销量（已售汇总，粗粒度）
 * @param shopId        所属店铺 ID
 * @param shopName      店铺名称
 * @param brandName     品牌名称（可空）
 * @author nona9961
 */
public record SearchCard(
        Long productId,
        String name,
        String coverImageUrl,
        Long minPrice,
        Long salesTotal,
        Long shopId,
        String shopName,
        String brandName
) {
}