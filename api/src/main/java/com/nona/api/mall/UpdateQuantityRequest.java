package com.nona.api.mall;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 修改购物车条目数量请求体（PUT /mall/cart/{skuId}）：按 SKU 定位条目，
 * 以绝对量替换现有数量（非增量）。
 * <p>
 * 数量必须为正且不超过该 SKU 买家可见可售上限；目标条目不存在时按
 * 不存在提示（列表过期场景）。
 *
 * @param quantity 新的数量（绝对量；正数且 ≤ 可售上限）
 *
 * @author nona9961
 */
public record UpdateQuantityRequest(
        @NotNull @Positive Integer quantity
) {
}