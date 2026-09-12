package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;

/**
 * 子订单履约状态（SubOrder 聚合状态字段，状态机值定型）。
 * <p>
 * 状态机（迁移守卫全部收敛在聚合方法，非法迁移抛
 * {@link com.nona.exceptions.BusinessException}，
 * 业务码 {@code order.sub_status_illegal}）：
 * <pre>
 * pending payment → paid → shipped → completed
 *     ├ cancel（待支付直接取消）→ cancelled
 *     ├ payment timeout（支付超时自动取消，库存回滚编排在用例层）
 *     │        → cancelled
 *     └ closeByTimeout（发货超时自动关单，退款编排由调用方部署——
 *            资金侧状态由退款单承载）→ closed
 * paid/shipped/completed → refunding → refunded（全阶段可退）
 * shipped → completed（确认收货 / 收货超时自动完成共用同一迁移，
 *           触发源由调用方语义区分）
 * </pre>
 * 语义钉死（契约）：
 * <ol>
 *     <li>迁移单向不可逆——cancelled / refunded / closed 为终态，无任何
 *         出边（待支付可取消除外，取消除外语义 = 仅 pending 可入 cancelled）；</li>
 *     <li>paid / shipped / completed 可进入 refunding（主动退款申请）；</li>
 *     <li>closed 仅由 paid 进入（发货超时针对已支付未发货子单；支付超时
 *         走 cancelled，与领域模型一致）；</li>
 *     <li>重复迁移（如重复支付、重复发货、重复完成）为非法，聚合守卫拒绝。</li>
 * </ol>
 *
 * @author nona9961
 */
public enum SubOrderStatus {

    /**
     * 待支付：下单生成后的初始态，等待整单支付。
     */
    PENDING_PAYMENT,

    /**
     * 已支付：支付成功推进（整单支付语义，全部子单同事务推进）。
     */
    PAID,

    /**
     * 已发货：商家发货（归属店铺校验 + 运单号定型后进入）。
     */
    SHIPPED,

    /**
     * 已完成：确认收货或收货超时自动完成。
     */
    COMPLETED,

    /**
     * 已取消：待支付直接取消 / 支付超时自动取消（终态）。
     */
    CANCELLED,

    /**
     * 退款中：主动退款申请推进（已支付/已发货/已完成可进入；终态前可逆过渡态）。
     */
    REFUNDING,

    /**
     * 已退款：退款成功（终态）。
     */
    REFUNDED,

    /**
     * 已关闭：发货超时自动关单（已支付未发货，终态；退款编排由调用方
     * 部署，与主动退款路径的 refunding → refunded 区分展示）。
     */
    CLOSED;

    /**
     * 按枚举名解析子单状态（查询参数/筛选映射用）；null 或未知值拒绝。
     *
     * @param name 枚举名（如 PENDING_PAYMENT）；null 或未知值拒绝
     * @return 匹配的子单状态
     * @throws BusinessException 非法状态（400 generic.validation_failed）
     */
    public static SubOrderStatus fromName(String name) {
        for (SubOrderStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法子单状态", 400);
    }
}