package com.nona.domain.payment.ports;

/**
 * 渠道侧业务结果（回调携带的最终结果，非受理结果）。
 *
 * @author nona9961
 */
public enum GatewayResult {

    /**
     * 成功（支付成功/退款成功）。
     */
    SUCCESS,

    /**
     * 失败（支付失败/退款拒绝——业务失败，渠道侧未完成，可重试语义由
     * 消费方按场景判定）。
     */
    FAIL
}