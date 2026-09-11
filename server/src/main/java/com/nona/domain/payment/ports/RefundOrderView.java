package com.nona.domain.payment.ports;

import com.nona.domain.payment.entity.RefundOrderStatus;

/**
 * 退款申请结果视图（退款编排申请/重试路径的返回载体，payment 域跨
 * 上下文冻结字段清单）：买家申请退款与发货超时系统退款
 * （系统退款入口）拿到退款单的引用与状态展示数据。
 * <p>
 * 语义：
 * <ul>
 *     <li>退款以子单为操作单元（一子单一退款单，sub_order_id 唯一）；
 *         金额 = 子单实付（创建时固化，退款金额=实付金额）；</li>
 *     <li>状态为退款单资金侧状态（PENDING = 已受理等待渠道回调 /
 *         FAILED = 可重试）——订单侧履约状态（退款中/已退款）由订单
 *         状态查询承载，本视图不冗余；</li>
 *     <li>{@code payNo} 原样回显（渠道受理锚点展示面）。</li>
 * </ul>
 * 本视图为退款单申请的创建/重试结果；退款推进/回补等生命周期入口由
 * 退款回调编排承载。
 *
 * @param refundOrderId  退款单 ID（RefundOrder 聚合根标识）
 * @param refundNo       退款单号（REF + 日期 + snowflake 后段，
 *                       渠道受理幂等键）
 * @param amount         退款金额（分，= 子单实付）
 * @param status         退款单状态（PENDING = 受理中等待回调；FAILED =
 *                       受理失败可重试）
 * @param payNo          关联支付单号（原交易定位回显）
 * @author nona9961
 */
public record RefundOrderView(Long refundOrderId, String refundNo, long amount,
                              RefundOrderStatus status, String payNo) {
}