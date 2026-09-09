package com.nona.api.mall;

/**
 * 发起支付请求体（WU-59 回注——形状钉死前端
 * mall-trading.types.ts {@code InitiatePaymentRequest}：POST /mall/payments）。
 * <p>
 * 语义对齐 PaymentUseCase.initiatePaymentWithView：按主单定位发起支付。
 *
 * @param masterOrderId 主订单 ID（必填）
 * @author nona9961
 */
public record InitiatePaymentRequest(Long masterOrderId) {
}