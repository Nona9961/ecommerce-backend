package com.nona.domain.payment.entity;

/**
 * 退款单状态（RefundOrder 聚合状态字段，状态机值定型——对齐领域模型
 * 冻结 Refund Aggregate：pending → succeeded / failed（failed is
 * retryable），本阶段冻结）。
 * <p>
 * 状态机（迁移守卫全部收敛在聚合方法，非法迁移抛
 * {@link com.nona.exceptions.BusinessException}，业务码
 * {@code payment.refund_status_illegal}）：
 * <pre>
 * PENDING → SUCCEEDED（REFUND 回调成功 markSucceeded，终态）
 * PENDING → FAILED（渠道受理拒绝 / REFUND 回调失败 markRefundFailed）
 * FAILED → PENDING（重试受理 recordAcceptance——同一退款单重新受理，
 *         渠道幂等键 refundNo 复用；可重试语义，领域模型冻结）
 * SUCCEEDED →（终态：无任何出边，资金已退还不可逆）
 * </pre>
 * 语义钉死（契约，退款编排守卫的判定依据）：
 * <ol>
 *     <li>创建即 PENDING（申请受理中/等待渠道开、退款结果异步到达——
 *         受理与回调分离的异步模型，受理成功不改变状态仅落位流水）；</li>
 *     <li>SUCCEEDED 为资金终态：退款成功不可逆（退款金额已退还买家），
 *         无任何出边；</li>
 *     <li>FAILED 可重试：渠道受理拒绝或退款失败回调后停留在 FAILED，
 *         买家对同一退款单重试受理（FAILED → PENDING 归位），订单侧
 *         停留退款中（领域模型：「failure keeps order in refunding
 *         (retryable)」）；</li>
 *     <li>同号重复退款回调（幂等命中）：已非 PENDING（SUCCEEDED/
 *         FAILED 终态或过渡态）再收到同号回调 → 状态守卫拒绝
 *         {@code payment.refund_status_illegal}，编排捕获后按「已处理
 *         应答」返回（重复回调只生效一次，不重放订单/库存编排）。</li>
 * </ol>
 * 本枚举为纯值定型（无手写字段/方法——解析/判定逻辑收敛在聚合方法或
 * 实现侧，契约演进只增不改，禁止向本枚举追加可实现逻辑）。
 *
 * @author nona9961
 */
public enum RefundOrderStatus {

    /**
     * 退款中（初始态）：退款单创建后的状态——系统即时受理（免平台
     * 人工）后等待渠道 REFUND 回调异步到达；可迁移出
     * （→ SUCCEEDED / FAILED）。
     */
    PENDING,

    /**
     * 已退款（终态）：REFUND 回调成功迁移——资金已退还，不可逆。
     */
    SUCCEEDED,

    /**
     * 已失败（可重试）：渠道受理拒绝或退款失败回调迁移——订单侧停留
     * 退款中，同一退款单可重试受理。
     */
    FAILED
}