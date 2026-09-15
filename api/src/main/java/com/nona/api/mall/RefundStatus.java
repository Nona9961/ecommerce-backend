package com.nona.api.mall;

/**
 * 退款单状态（线上契约枚举，接口层冻结，形状钉死前端
 * mall-trading.types.ts {@code RefundStatus} 3 值逐名对应，与后端
 * RefundOrderStatus 状态机一致：FAILED 可重试）。
 * <p>
 * 枚举名即线上 JSON 值（Jackson 同名序列化）；RefundView.status 承载。
 *
 * @author nona9961
 */
public enum RefundStatus {

    /**
     * 受理中等待渠道回调。
     */
    PENDING,

    /**
     * 已退款（成功回调收敛，终态）。
     */
    SUCCEEDED,

    /**
     * 受理失败（可重试——retry 复用同一退款单号）。
     */
    FAILED
}