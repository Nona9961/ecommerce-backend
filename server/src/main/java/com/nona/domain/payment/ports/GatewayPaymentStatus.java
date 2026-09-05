package com.nona.domain.payment.ports;

/**
 * 渠道侧支付状态（query 返回；受理后渠道侧视角的最终/中间状态）。
 * <p>
 * 与业务侧支付单状态机（待支付→已支付/失败/关闭）是两套口径：本枚举
 * 只描述「渠道侧这笔交易现在的样子」，业务侧状态迁移由支付单聚合驱动。
 *
 * @author nona9961
 */
public enum GatewayPaymentStatus {

    /**
     * 支付中（渠道已受理、结果未定——含「永不回调」场景的渠道侧常态）。
     */
    WAIT_PAY,

    /**
     * 渠道侧支付成功。
     */
    SUCCESS,

    /**
     * 渠道侧支付失败（终结态）。
     */
    FAIL
}