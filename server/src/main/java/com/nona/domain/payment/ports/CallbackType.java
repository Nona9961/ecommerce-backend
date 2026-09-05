package com.nona.domain.payment.ports;

/**
 * 渠道回调类型：区分支付回调与退款回调（真实渠道两类回调 payload 各
 * 自字段形态不同，标准化后经统一回调入口进入）。
 *
 * @author nona9961
 */
public enum CallbackType {

    /**
     * 支付回调（acquire 受理后渠道异步回传支付结果）。
     */
    PAY,

    /**
     * 退款回调（refund 受理后渠道异步回传退款结果）。
     */
    REFUND
}