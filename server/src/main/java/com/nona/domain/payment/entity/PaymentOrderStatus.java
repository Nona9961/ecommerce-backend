package com.nona.domain.payment.entity;

/**
 * 支付单状态（PaymentOrder 聚合状态字段，状态机值定型，本阶段冻结）。
 * <p>
 * 状态机（迁移守卫全部收敛在聚合方法，非法迁移抛
 * {@link com.nona.exceptions.BusinessException}，业务码
 * {@code payment.status_illegal}）：
 * <pre>
 * PENDING_PAYMENT → PAID（守卫：回调成功 markPaid）
 * PENDING_PAYMENT → FAILED（回调失败 markFailed，订单保持待支付等待超时）
 * PENDING_PAYMENT → CLOSED（待支付关单：主动取消/支付超时 closePay）
 * FAILED → CLOSED（失败单显式收口：超时调度重复打向失败单，非仅靠超时）
 * CLOSED →（幂等：重复关单无害返回；取消与超时调度重放不报错）
 * PAID →（终态：无任何出边，资金锁定）
 * </pre>
 * 语义钉死（契约，防线二状态守卫的判定依据）：
 * <ol>
 *     <li>迁移仅 {@code PENDING_PAYMENT} 可出——到达 PAID/FAILED/CLOSED 后除
 *         FAILED→CLOSED 外的任何再迁移均为非法，聚合守卫拒绝（告警日志由
 *         实现方落位）；</li>
 *     <li>同号重复回调（幂等命中）：已非待支付且流水号与本次相同——状态守卫
 *         拒绝并抛 {@code payment.status_illegal}，回调编排捕获后按「已处理
 *         应答」返回渠道（重复回调只生效一次，不重放订单/库存编排）；</li>
 *     <li>PAID 为资金终态：已支付支付单不可关单（资金已锁定，关单走退款流）；</li>
 *     <li>CLOSED 幂等：对已关闭支付单重复 closePay 幂等成功（超时调度与主动
 *         取消并发重放的常态路径，不产生告警噪音）。</li>
 * </ol>
 * 本枚举为纯值定型（无手写字段/方法——解析/判定逻辑收敛在聚合方法或实现侧，
 * 契约演进只增不改，禁止向本枚举追加可实现逻辑）。
 *
 * @author nona9961
 */
public enum PaymentOrderStatus {

    /**
     * 待支付：支付单创建后的初始态（下单成功即创建，等待渠道受理与回调）。
     */
    PENDING_PAYMENT,

    /**
     * 已支付：支付成功回调迁移（金额/流水校验通过后进入；资金终态）。
     */
    PAID,

    /**
     * 已失败：支付失败回调迁移（失败回调也落位渠道流水，唯一约束照常生效）。
     */
    FAILED,

    /**
     * 已关闭：待支付主动取消/支付超时关单，或失败单经超时显式收口（终态）。
     */
    CLOSED
}