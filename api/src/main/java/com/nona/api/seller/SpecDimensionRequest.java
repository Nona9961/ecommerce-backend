package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 商品规格模板维度请求体（{@link SpecTemplateRequest} 的组成单元）。
 * <p>
 * 一个规格维度 = 维度名 + 值列表（SKU 组合 = 各维度值集笛卡尔积）。
 * 维度名必填非空；值列表必填非空且每个值必填非空（空值维度生成不出
 * 可售组合）；同模板内维度名唯一、维度内值唯一由领域值对象构造路径
 * 校验（本层仅格式校验）。
 *
 * @param name   维度名称（必填非空）
 * @param values 维度值列表（必填非空，元素非空）
 */
public record SpecDimensionRequest(
        @NotBlank(message = "维度名不能为空") String name,
        @NotEmpty(message = "规格值不能为空") List<@NotBlank(message = "规格值不能为空") String> values
) {
}