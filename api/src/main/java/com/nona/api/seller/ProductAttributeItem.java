package com.nona.api.seller;

/**
 * 商品自定义属性条目响应体（详情内嵌与属性管理响应共用）。
 * <p>
 * 键值对形态：同一商品内键唯一（重复键无业务意义，聚合内唯一性
 * 保证）；键必填，值可为空串（值可空语义，由商家决定是否填写）。
 *
 * @param id    属性 ID
 * @param key   属性键（同商品内唯一，必填）
 * @param value 属性值（可空）
 */
public record ProductAttributeItem(
        Long id,
        String key,
        String value
) {
}