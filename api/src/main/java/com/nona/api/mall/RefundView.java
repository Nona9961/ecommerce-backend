package com.nona.api.mall;

/**
 * 退款单视图（申请/重试路径返回，形状钉死前端
 * mall-trading.types.ts {@code RefundView}）。
 * <p>
 * 金额纪律：amount 为分（= 子单实付，api-client 换算为元下行）。
 *
 * @param refundOrderId 退款单 ID
 * @param refundNo      退款单号（TD-13：REF + 日期 + snowflake 后段）
 * @param amount        退款金额（分，= 子单实付）
 * @param status        退款单状态（RefundStatus 3 值；FAILED 可重试）
 * @param payNo         原交易支付单号回显
 * @author nona9961
 */
public record RefundView(
        Long refundOrderId,
        String refundNo,
        long amount,
        RefundStatus status,
        String payNo
) {
}