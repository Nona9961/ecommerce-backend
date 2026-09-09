package com.nona.api.mall;

/**
 * 发起支付结果（WU-59 回注——形状钉死前端 tradingApi.ts
 * {@code InitiatePaymentWire} 7 字段逐名对应，金额折分为元下行）。
 * <p>
 * 字段语义 = domain/payment/ports {@code PaymentAcquireView}（PendingPayment
 * + AcquireResult 融合，8 字段含 payTimeoutMillis——规则回显仅供校验，
 * 不对外）的对外投影：accepted / paymentOrderId / payNo / amount /
 * timeoutAt / channelTxnNo / cashierToken。
 * <p>
 * 受理 ≠ 支付结果：accepted=true 仅代表渠道受理，最终结果异步到达——
 * 收银台轮询订单视图 payment.status 收敛（PAID 成功 / FAILED 失败）。
 * <p>
 * 金额纪律：amount 为分（= 主单实付）；timeoutAt 为 ISO-8601 字符串
 * （渠道拒绝受理时无超时语义，可空防御形态）；cashierToken 可空
 * （mock 渠道：mock-cashier 前缀）。
 *
 * @param accepted       是否受理成功（false = 渠道拒绝受理，无后续回调）
 * @param paymentOrderId 支付单 ID
 * @param payNo          支付单号（业务单号，TD-13）
 * @param amount         支付金额（分，= 主单实付）
 * @param timeoutAt      支付超时截止时间（ISO-8601 字符串；可空）
 * @param channelTxnNo   渠道流水号（受理即生成）
 * @param cashierToken   收银台标识（可空）
 * @author nona9961
 */
public record InitiatePaymentResult(
        boolean accepted,
        Long paymentOrderId,
        String payNo,
        long amount,
        String timeoutAt,
        String channelTxnNo,
        String cashierToken
) {
}