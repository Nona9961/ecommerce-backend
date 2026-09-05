package com.nona.domain.payment.ports;

/**
 * 渠道回调校验通过后的标准化回调事件（handleCallback 返回）：供业务侧
 * 回调编排消费的「已可信」结果——字段与原始载荷同构，语义为「经过渠道
 * 实现校验（验签模拟/参数防御）后的回调事实」。
 * <p>
 * 业务侧编排（幂等三层防线、支付/退款状态迁移）消费本结构；金额一致性
 * （回调金额 = 受理金额）核对由支付单聚合承载。
 *
 * @param type          回调类型
 * @param payNo         业务支付单号
 * @param refundNo      业务退款单号（type=REFUND 时非空，PAY 时为空）
 * @param result        渠道侧业务结果
 * @param channelTxnNo  渠道流水号
 * @param amountCents   回调金额（分，已校验为正）
 * @author nona9961
 */
public record ValidatedCallback(CallbackType type, String payNo, String refundNo,
                                GatewayResult result, String channelTxnNo, Long amountCents) {
}