package com.nona.domain.payment.ports;

import java.time.Instant;

/**
 * 待支付支付单视图（PaymentPort 创建契约的返回载体，payment 域跨上下文
 * 冻结字段清单）：下单编排创建支付单后拿到的引用与展示数据。
 * <p>
 * 语义：
 * <ul>
 *     <li>支付单与主单一对一（一期不支持分次支付）；金额 =
 *         主单实付（创建时固化，防中途改价）；</li>
 *     <li>{@code payTimeoutMillis} 为下单编排传入的支付超时时长（订单域
 *         D1-o1 规则，取值承载于 TimeoutType.ORDER_PAY），本视图携带
 *         原值回显供校验（防实现侧静默改写规则）；</li>
 *     <li>{@code timeoutAt} = 创建时刻 + 支付超时时长（B8.3 注册截止时间
 *         的落库形态由实现承载：payment_order.timeout_at 冗余列，超时
 *         引擎按 (status, timeout_at) 索引扫描，接线随超时引擎落地）。</li>
 * </ul>
 * 本视图为待支付支付单的创建结果；支付推进/关单/回调等生命周期入口
 * （onPaid 编排、closePay）在后续 WU 冻结。
 *
 * @param paymentOrderId 支付单 ID（PaymentOrder 聚合根标识）
 * @param payNo          支付单号（TD-13：PAY + 日期 + snowflake 后段）
 * @param amount         支付金额（分，= 主单实付）
 * @param payTimeoutMillis 支付超时时长（毫秒，创建时传入的规则值回显）
 * @param timeoutAt      支付超时截止时间（创建时刻 + 超时时长）
 * @author nona9961
 */
public record PendingPayment(Long paymentOrderId, String payNo, long amount,
                             long payTimeoutMillis, Instant timeoutAt) {
}