package com.nona.domain.payment.ports;

/**
 * 渠道回调原始载荷（handleCallback 入参，标准化结构）：真实渠道回调
 * payload 各渠道字段形态不同，接真实渠道时由渠道实现（inf 层）把原始
 * 报文验签/翻译成本结构后再进入本契约——本接口不暴露任何渠道特有字段。
 * <p>
 * 回调语义：渠道受理后的异步结果通知，到达顺序与受理一一对应（同一
 * payNo 同一类型至多一次成功受理回调；重复/乱序回调的幂等防线由业务
 * 侧回调编排承载，不在本契约内）。
 *
 * @param type          回调类型（支付/退款）
 * @param payNo         业务支付单号（受理时回显原值）
 * @param refundNo      业务退款单号（type=REFUND 时必填，PAY 时必空）
 * @param result        渠道侧业务结果
 * @param channelTxnNo  渠道流水号（支付回调 = acquire 受理流水；退款回调
 *                       = refund 受理流水；必填）
 * @param amountCents   回调金额（分，渠道回传的实收/实退金额；业务侧按
 *                       此核对受理金额一致性，本契约只校验正数）
 * @author nona9961
 */
public record GatewayCallbackPayload(CallbackType type, String payNo, String refundNo,
                                     GatewayResult result, String channelTxnNo, Long amountCents) {
}