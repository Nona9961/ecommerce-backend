package com.nona.domain.payment.ports;

import java.time.Instant;

/**
 * 发起支付受理视图（PaymentUseCase.initiatePaymentWithView 返回载体，
 * payment 域跨上下文冻结字段清单——WU-59 已裁决契约补充 2：PendingPayment
 * 与 AcquireResult 融合视图，8 字段）。
 * <p>
 * 语义：
 * <ul>
 *     <li>{@code payTimeoutMillis} 为创建时传入的支付超时时长规则值
 *         回显（TimeoutType.ORDER_PAY；防实现侧静默改写规则——对外
 *         投影 InitiatePaymentResult 不承载该字段，仅编排内校验面）；</li>
 *     <li>{@code accepted=false}（渠道拒绝受理）时无后续回调，业务侧
 *         按受理失败处理；<b>受理 ≠ 支付结果</b>，最终结果经回调异步
 *         到达（轮询订单视图 payment.status 收敛）；</li>
 *     <li>金额单位分（= 主单实付快照）；timeoutAt = 创建时刻 + 超时
 *         时长（B8.3 30 分钟）。</li>
 * </ul>
 *
 * @param paymentOrderId     支付单 ID（PaymentOrder 聚合根标识）
 * @param payNo              支付单号（TD-13：PAY + 日期 + snowflake 后段）
 * @param amount             支付金额（分，= 主单实付）
 * @param payTimeoutMillis   支付超时时长（毫秒，规则值回显）
 * @param timeoutAt          支付超时截止时间（创建时刻 + 超时时长）
 * @param accepted           是否受理成功（false = 渠道拒绝受理，无后续回调）
 * @param channelTxnNo       渠道流水号（受理即生成）
 * @param cashierToken       收银台标识（mock 渠道：mock-cashier 前缀；
 *                           可空防御形态）
 * @author nona9961
 */
public record PaymentAcquireView(
        Long paymentOrderId,
        String payNo,
        long amount,
        long payTimeoutMillis,
        Instant timeoutAt,
        boolean accepted,
        String channelTxnNo,
        String cashierToken
) {
}