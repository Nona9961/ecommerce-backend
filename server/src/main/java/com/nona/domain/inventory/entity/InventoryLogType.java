package com.nona.domain.inventory.entity;

/**
 * 库存流水类型（inventory_log 行语义全集，全量定义——后续阶段仅消费
 * 本枚举，禁止新增类型绕过审计口径）。
 * <p>
 * 各类型形态约定（方向由类型界定，delta 一律表达数量幅度——订单驱动
 * 型恒为正，手动调整型可为正负）：
 * <ul>
 *     <li>{@link #PREOCCUPY}：订单预占——available −d、held +d；orderId 必填；</li>
 *     <li>{@link #CONFIRM}：支付确认扣减——held −d、sold +d；orderId 必填；</li>
 *     <li>{@link #ROLLBACK}：预占回滚（取消/超时释放）——held −d、available +d；
 *         orderId 必填；</li>
 *     <li>{@link #MANUAL_ADJUST}：商家手工调整可售——仅 available 变动（符号含
 *         方向：正=增可售、负=减可售），预占/已售不动；无订单上下文，
 *         orderId 恒为空，操作人必填（原因可选）；</li>
 *     <li>{@link #REFUND_RESTORE}：退款/未发货关单回补——sold −d、available +d；
 *         orderId 必填（幂等键随订单落地）。</li>
 * </ul>
 * 幂等键 (orderId, skuId, type)：同一订单同一 SKU 的同一类型变动只允许
 * 一次，重复请求由唯一约束拒绝（手动调整型无 orderId，不参与订单幂等）。
 *
 * @author nona9961
 */
public enum InventoryLogType {

    /**
     * 订单预占（订单驱动，orderId 必填）。
     */
    PREOCCUPY,

    /**
     * 确认扣减（支付成功驱动，orderId 必填）。
     */
    CONFIRM,

    /**
     * 预占回滚（取消/超时释放，orderId 必填）。
     */
    ROLLBACK,

    /**
     * 商家手工调整可售（无订单上下文，orderId 恒空；操作人必填）。
     */
    MANUAL_ADJUST,

    /**
     * 退款回补（未发货退款/发货超时关单回补可售，orderId 必填）。
     */
    REFUND_RESTORE
}