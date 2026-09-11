package com.nona.domain.payment.ports;

/**
 * 支付受理请求（acquire 入参）：业务侧已生成的支付单号 + 支付金额。
 * <p>
 * 形态按真实渠道设计：渠道受理只关心「这是哪笔业务单、多少钱」，业务
 * 单号（payNo）由商户侧生成（单号生成收敛在支付单创建处，本接口
 * 不承担单号生成）；受理结果异步到达（回调），受理本身不代表支付结果。
 *
 * @param payNo      业务支付单号（幂等键的一部分——渠道侧按此单号受理
 *                   唯一一笔交易；非法（空白）被渠道拒绝）
 * @param amountCents 支付金额（分，必须为正）
 * @author nona9961
 */
public record AcquireRequest(String payNo, Long amountCents) {
}