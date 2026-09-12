package com.nona.api.internal;

/**
 * 回调类型（契约面枚举，接口层冻结）：区分支付回调与退款回调——真实
 * 渠道两类回调 payload 字段形态不同，标准化后经统一回调入口进入。
 * <p>
 * 与 payment 域端口枚举 {@code com.nona.domain.payment.ports.CallbackType}
 * 同名同值逐一对应（接口层自持线上契约，遵循 api 模块不依赖 domain 的
 * 分层——同 {@code com.nona.api.mall.PaymentStatus} 先例）；枚举名即线上
 * JSON 值（Jackson 同名序列化），控制器接线时按名直转。
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