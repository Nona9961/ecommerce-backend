package com.nona.domain.payment.ports;

/**
 * 支付端口（payment 跨上下文契约，创建面契约随本阶段冻结——下单编排
 * 消费；实现接线归支付域落位，与关单端口「契约声明 / 实现接线」同模式）。
 * <p>
 * 语义约束：
 * <ul>
 *     <li><b>待支付创建</b>：下单提交成功即创建支付单（进入待支付，
 *         关联主单、金额 = 主单实付）；同一主单重复调用由实现的幂等/
 *         唯一约束兜底（防线面随支付单落地）；</li>
 *     <li><b>支付超时注册</b>：创建时以 payTimeoutMillis 注册支付超时
 *         deadline（待支付 30 分钟自动关单回滚）——截止时间由实现
 *         落库为 payment_order.timeout_at 冗余列（deadline 列承载于
 *         order/payment 侧，(status, timeout_at) 复合索引，超时引擎扫描
 *         面随引擎接线落位）；</li>
 *     <li><b>规则值归属</b>：支付超时时长归属订单域（30 分钟，
 *         取值承载于 {@code inf.timeout.TimeoutType.ORDER_PAY}），由
 *         下单编排传入而非 payment 域自定（订单域主管，支付域配合）；</li>
 *     <li><b>租户形态</b>：支付单为 global 表（买家维度），下单用例的
 *         提权写段内调用即可，无需店铺租户语义。</li>
 * </ul>
 * 契约演进只增不改：关单/回调/推进成员已冻结，不修改既有签名。
 *
 * @author nona9961
 */
public interface PaymentPort {

    /**
     * 创建待支付支付单并注册支付超时 deadline（签名随本阶段冻结；
     * 实现接线归支付域落位）。
     *
     * @param masterOrderId   主订单 ID（支付单关联锚点，一对一）
     * @param paidAmount      支付金额（分，= 主单实付；非负）
     * @param payTimeoutMillis 支付超时时长（毫秒，下单编排以
     *                         TimeoutType.ORDER_PAY.durationMillis() 传入）
     * @return 待支付支付单视图（含 payNo/金额/截止时间）
     */
    PendingPayment createPendingPayment(Long masterOrderId, long paidAmount, long payTimeoutMillis);

    /**
     * 待支付支付单关单（关单契约声明，实现接线归支付域落位；消费方 = 主动
     * 取消与支付超时编排，同事务：order.cancel → 库存回滚 → closePay）。
     * <p>
     * 语义（与支付单聚合状态机 {@code close()} 对齐，端口层补装载与不存在
     * 呈现）：
     * <ul>
     *     <li>待支付 / 已失败 → 关闭（失败单显式收口，不仅靠超时）；</li>
     *     <li>已关闭 → 幂等成功（超时调度与主动取消重放不报错，超时
     *         handler 幂等语义）；</li>
     *     <li>已支付 → 拒绝（资金已锁定，关单走退款流 退款流；
     *         {@code payment.status_illegal}）；</li>
     *     <li>支付单不存在 → {@code payment.not_found}（404）。</li>
     * </ul>
     *
     * @param payNo 支付单号（必填非空）
     */
    void closePay(String payNo);
}