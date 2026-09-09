package com.nona.api.mall;

/**
 * 退款申请请求体（WU-59 回注——形状钉死前端
 * mall-trading.types.ts {@code RefundApplyRequest}：POST
 * /mall/sub-orders/{subOrderId}/refunds）。
 * <p>
 * 语义对齐 RefundUseCase.applyRefundByBuyer：原因可空（一期原因仅
 * 展示语义，非渠道必填）。
 *
 * @param reason 退款原因（可空）
 * @author nona9961
 */
public record RefundApplyRequest(String reason) {
}