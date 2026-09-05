package com.nona.domain.payment.ports;

/**
 * 退款受理请求（refund 入参）：业务支付单号 + 业务退款单号 + 退款金额。
 * <p>
 * 形态按真实渠道设计（与支付同构的异步回调模型）：refund 只表达「受理」，
 * 退款结果经 REFUND 类型回调（{@link GatewayCallbackPayload}）异步到达；
 * 退款单号由业务侧生成（退款单创建处），渠道侧按 payNo 定位原交易校验
 * 退款金额上限（超付校验由业务侧退款单聚合承载，渠道侧只校验正数）。
 *
 * @param payNo       业务支付单号（原交易定位）
 * @param refundNo    业务退款单号（幂等键的一部分——同一退款单只受理一次）
 * @param amountCents 退款金额（分，必须为正）
 * @author nona9961
 */
public record RefundRequest(String payNo, String refundNo, Long amountCents) {
}