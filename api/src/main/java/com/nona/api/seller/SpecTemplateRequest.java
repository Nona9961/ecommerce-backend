package com.nona.api.seller;

import jakarta.validation.Valid;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 商品规格模板整体替换请求体。
 * <p>
 * 规格模板是 SKU 集的生成依据：本端点整体替换模板并重建 SKU 集（同组合
 * 保留既有 SKU 的价格/启用/身份；新增组合默认未定价且停用）。空
 * dimensions（null 或空列表）= 空模板，语义为清空本商品 SKU 集。
 * 维度名/值约束（非空、唯一）由领域值对象构造路径校验。
 *
 * @param dimensions 规格维度列表（按配置序；null 或空 = 空模板清空 SKU 集）
 */
public record SpecTemplateRequest(@Valid List<SpecDimensionRequest> dimensions) {

    /**
     * 紧凑构造器：防御 null，无维度以空列表呈现（空模板语义）。
     */
    public SpecTemplateRequest {
        dimensions = Objects.requireNonNullElse(dimensions, Collections.emptyList());
    }
}