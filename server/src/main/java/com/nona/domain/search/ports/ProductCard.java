package com.nona.domain.search.ports;

/**
 * 搜索结果卡片（搜索列表项视图，跨域聚合读模型的一期冻结字段）。
 * <p>
 * 字段语义：
 * <ul>
 *     <li>minPrice：在售 SKU 起步价（分）——商品下发 SKU 价格各异，
 *         搜索卡片以最低售价呈现（「xx 元起」语义）；</li>
 *     <li>salesTotal：销量（镜像表 inventory_item.sold 按商品汇总；
 *         粗粒度热度展示，非精确口径）；</li>
 *     <li>coverImageUrl：主图（在售不变量保证商品主图齐备，防御形态
 *         由实现 LEFT JOIN + 兜底呈现）；</li>
 *     <li>brandName：品牌名（无品牌商品为 null，卡片按无品牌呈现）。</li>
 * </ul>
 * 有货态不设字段：视图侧已过滤可售汇总 &gt; 0 的商品（TD-08：结果卡片仅
 * 粗粒度有货态，视图过滤保证进入本载体的商品必有货）。
 *
 * @param productId     商品 ID
 * @param name          商品名称（标题）
 * @param coverImageUrl 主图 URL（可空防御形态）
 * @param minPrice      在售 SKU 最低售价（分；非空——视图过滤无价商品）
 * @param salesTotal    销量（已售汇总，粗粒度）
 * @param shopId        所属店铺 ID
 * @param shopName      店铺名称
 * @param brandName     品牌名称（可空）
 * @author nona9961
 */
public record ProductCard(
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