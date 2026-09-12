package com.nona.api.internal;

/**
 * 渠道侧业务结果（契约面枚举，接口层冻结）：回调携带的最终结果，非
 * 受理结果。
 * <p>
 * 与 payment 域端口枚举 {@code com.nona.domain.payment.ports.GatewayResult}
 * 同名同值逐一对应（接口层自持线上契约，遵循 api 模块不依赖 domain 的
 * 分层）；枚举名即线上 JSON 值（Jackson 同名序列化），控制器接线时按
 * 名直转。
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