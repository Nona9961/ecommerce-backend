package com.nona.api.mall;

/**
 * 买家商品详情响应（商品详情页数据契约——主图/价格/描述/
 * 运费说明/店铺 + SKU 规格联动数据一次成型）。
 * <p>
 * 主库强一致读（详情页含精确库存与价格，须主库强一致）——
 * 服务端在售校验：不存在/草稿/待审核/已下架统一按不存在呈现（404，
 * 下架后买家不可见）；待审草稿内容不可见（视图=生效内容）。
 * <p>
 * 字段与买家商品视图（ProductQueryFacade）同构（映射收敛在应用层）：
 * 规格联动前端按 specDimensions × skus（specHash/specSummary 对齐模板
 * 展开序）渲染；available=0 的 SKU 置灰不可选（无库存 SKU 置灰）；
 * freight=null 表示商品未绑运费模板（运费区按无模板呈现）；金额单位分。
 *
 * @param productId      商品 ID
 * @param shopId         归属店铺 ID
 * @param name           商品名称
 * @param description    商品描述（可空）
 * @param mainImageUrl   主图 URL（在售商品必有主图）
 * @param imageUrls      图集 URL 列表（按加入序，含主图）
 * @param attributes     自定义属性键值对（按加入序）
 * @param specDimensions 规格维度结构（规格选择区渲染）
 * @param skus           SKU 行（价格 + 可售量，按模板展开序）
 * @param freight        绑定运费模板规则概要（未绑定=null）
 * @param shop           店铺卡片（店名/logo）
 * @author nona9961
 */
public record BuyerProductDetail(
        Long productId,
        Long shopId,
        String name,
        String description,
        String mainImageUrl,
        java.util.List<String> imageUrls,
        java.util.List<BuyerAttributeView> attributes,
        java.util.List<BuyerSpecDimensionView> specDimensions,
        java.util.List<BuyerSkuView> skus,
        BuyerFreightView freight,
        BuyerShopView shop
) {
}
