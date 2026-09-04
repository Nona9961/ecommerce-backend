package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 商品自定义属性请求体：新增与编辑（改键/改值）共用的契约。
 * <p>
 * 键必填且同商品内唯一（编辑改键时与其余属性冲突即拒绝）；值可空。
 *
 * @param key   属性键（必填）
 * @param value 属性值（可空）
 * @author nona9961
 */
public record ProductAttributeRequest(
        @NotBlank(message = "属性键不能为空") String key,
        String value
) {
}