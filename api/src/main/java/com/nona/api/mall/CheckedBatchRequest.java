package com.nona.api.mall;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 购物车条目勾选/取消勾选请求体（PUT /mall/cart/checked）：批量设置
 * 指定 SKU 条目的结算勾选标记。
 * <p>
 * 单条勾选 = 列表单元素；目标条目不存在按不存在提示。勾选标记持久化
 * （跨请求保持，结算只取勾选项）。
 *
 * @param skuIds  目标条目 SKU ID 列表（条目按 SKU 在购物车内唯一）
 * @param checked 勾选状态（true 勾选 / false 取消勾选）
 *
 * @author nona9961
 */
public record CheckedBatchRequest(
        @NotEmpty List<@NotNull Long> skuIds,
        @NotNull Boolean checked
) {
}