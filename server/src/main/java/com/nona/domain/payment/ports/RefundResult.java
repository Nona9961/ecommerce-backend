package com.nona.domain.payment.ports;

/**
 * 退款受理结果（refund 返回）：渠道是否受理 + 渠道侧退款流水号。
 * <p>
 * 受理 ≠ 退款结果：业务侧收到本结果只能推进「退款处理中」，最终结果经
 * REFUND 类型回调异步到达（回调 payload 中 channelTxnNo 字段承载本退款
 * 流水号）。受理被拒绝时无后续回调，业务侧按退款受理失败处理（重试
 * 语义由消费方退款单编排）。
 *
 * @param accepted           是否受理成功（false = 渠道拒绝受理，无后续回调）
 * @param payNo              业务支付单号（原样回显）
 * @param refundNo           业务退款单号（原样回显）
 * @param channelRefundTxnNo 渠道退款流水号（受理即生成；退款成功回调携带
 *                           同一流水号）
 * @author nona9961
 */
public record RefundResult(boolean accepted, String payNo, String refundNo, String channelRefundTxnNo) {
}