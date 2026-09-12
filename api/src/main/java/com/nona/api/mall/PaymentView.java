package com.nona.api.mall;

/**
 * 待支付支付单视图（订单视图嵌套，WU-59 回注——形状钉死前端
 * mall-trading.types.ts {@code PaymentView}）。
 * <p>
 * 语义：主单待支付时非空（收银台金额/超时/状态收敛数据源——
 * PayPage 轮询 order.payment.status 收敛 PAID/FAILED）；非待支付状态
 * 订单为 null（BuyerOrderQuery 装配语义）。
 * <p>
 * 时间形态：timeoutAt 为 ISO-8601 字符串（Instant.toString，UTC——
 * FavoriteItem.createTime 先例，无时区语义；前端 JS Date 直接解析）。
 *
 * @param paymentOrderId 支付单 ID
 * @param payNo          支付单号（TD-13：PAY + 日期 + snowflake 后段）
 * @param amount         支付金额（分，= 主单实付）
 * @param timeoutAt      支付超时截止时间（ISO-8601 字符串）
 * @param status         支付单状态（PaymentOrderStatus 4 值，枚举名序列化）
 * @author nona9961
 */
public record PaymentView(
        Long paymentOrderId,
        String payNo,
        long amount,
        String timeoutAt,
        PaymentStatus status
) {
}