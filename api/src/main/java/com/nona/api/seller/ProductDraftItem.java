package com.nona.api.seller;

/**
 * 商品草稿列表项响应体（商家端草稿列表的概要行）。
 * <p>
 * 列表行只承载概要信息（名称/状态/引用/子项计数），详情经
 * {@link ProductDetail} 获取（含图片与属性完整列表）。
 *
 * @param id             商品 ID
 * @param name           商品名称
 * @param status         商品状态（一期恒 DRAFT——草稿可保存不生效，无在售路径）
 * @param categoryId     平台类目 ID（可空）
 * @param brandId        品牌 ID（可空）
 * @param imageCount     图片引用数
 * @param attributeCount 自定义属性数
 */
public record ProductDraftItem(
        Long id,
        String name,
        String status,
        Long categoryId,
        Long brandId,
        int imageCount,
        int attributeCount
) {
}