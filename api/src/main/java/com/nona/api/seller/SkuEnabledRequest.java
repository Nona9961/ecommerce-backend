package com.nona.api.seller;

import jakarta.validation.constraints.NotNull;

/**
 * 商品 SKU 启停请求体。
 * <p>
 * 启用状态独立切换（停用 SKU 不出售）；新生成的 SKU 默认停用
 * （fail-closed：商家显式启用）。目标 SKU 必须属于请求商品，否则 404。
 *
 * @param enabled 是否启用（必填）
 */
public record SkuEnabledRequest(
        @NotNull(message = "启用状态不能为空") Boolean enabled
) {
}