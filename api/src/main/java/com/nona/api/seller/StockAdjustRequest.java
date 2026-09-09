package com.nona.api.seller;

/**
 * 库存手工调整请求体（WU-47 约定形状：PUT /seller/inventory/{skuId}，
 * 对齐 InventoryUseCase.adjustStock：仅可售变动（delta 带符号）、调整
 * 后非负、每笔调整必有流水；操作人由后端认证上下文定位（前端不传）。
 *
 * @param delta  可售调整量（带符号：正=增可售、负=减可售；非零，0 由
 *               服务端拒绝）
 * @param reason 调整原因（可选；手动调整型流水原因不强制）
 * @author nona9961
 */
public record StockAdjustRequest(
        int delta,
        String reason
) {
}