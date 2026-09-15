package com.nona.api.mall;

/**
 * 支付单状态（线上契约枚举，接口层冻结，形状钉死前端
 * mall-trading.types.ts {@code PaymentStatus} 4 值逐名对应，与后端
 * PaymentOrderStatus 状态机一致）。
 * <p>
 * 枚举名即线上 JSON 值（Jackson 同名序列化）；PaymentView.status 承载。
 *
 * @author nona9961
 */
public enum PaymentStatus {

    /**
     * 待支付（受理后、回调前）。
     */
    PENDING_PAYMENT,

    /**
     * 已支付（成功回调收敛）。
     */
    PAID,

    /**
     * 支付失败（失败回调收敛）。
     */
    FAILED,

    /**
     * 已关闭（超时关单/取消关单等终态）。
     */
    CLOSED
}