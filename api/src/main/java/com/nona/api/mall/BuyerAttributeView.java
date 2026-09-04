package com.nona.api.mall;

/**
 * 买家商品详情-自定义属性条目（键值对）。
 *
 * @param key   属性键
 * @param value 属性值（可空）
 * @author nona9961
 */
public record BuyerAttributeView(String key, String value) {
}
