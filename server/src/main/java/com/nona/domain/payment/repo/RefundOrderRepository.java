package com.nona.domain.payment.repo;

import com.nona.domain.payment.entity.RefundOrder;
import com.nona.persistence.BaseRepository;

/**
 * 退款单仓储接口：退款单聚合根的持久化契约（refund_order 主表 +
 * refund_callback_log 从表，买家维度 global）。
 * <p>
 * 实现在基础设施层（继承 DifferRepository：主表快照追踪 + 变更集驱动
 * 落库——主表唯一行；从表留痕集合经 getOther 装载/追加插行，Cart/
 * CartItem、PaymentOrder/payment_callback_log 判例同构）；领域层只
 * 依赖本契约，不感知 JPA。聚合根主表以独立主键（refundOrderId）承载
 * 身份。
 * <p>
 * 唯一约束契约（DDL 落地时按本 javadoc 核对）：
 * <ul>
 *     <li>{@code refund_no} 唯一（业务退款单号）；</li>
 *     <li>{@code sub_order_id} 唯一（一子单一生至多一个退款单——重复
 *         申请的最后防线，正常路径由申请编排「防重判定」守护；FAILED
 *         可重试复用同一单，不新建行）；</li>
 *     <li>{@code channel_refund_txn_no} <b>不建</b>唯一约束（FAILED
 *         重试受理覆盖更新流水，唯一约束会拦覆盖；流水一致防线由聚合
 *         方法守卫承载——异号即渠道事故 409）。</li>
 * </ul>
 * 查询契约遵循「契约演进只增不改」：本阶段冻结按退款单号（回调装载）
 * 与按子单（申请防重/超时短路）两装载；退款单列表/详情等查询面随
 * 消费编排扩展。
 *
 * @author nona9961
 */
public interface RefundOrderRepository extends BaseRepository<Long, RefundOrder> {

    /**
     * 按退款单号装载（退款回调处理消费面：handleRefundCallback 装载锚点）。
     *
     * @param refundNo 退款单号（必填非空）
     * @return 退款单；不存在时返回 {@code null}（调用方按
     *         {@code payment.refund_not_found} 呈现，孤儿回调不产生
     *         处理路径）
     */
    RefundOrder findByRefundNo(String refundNo);

    /**
     * 按操作单元子单装载（申请防重 + 发货超时幂等短路消费面：一子单
     * 一生至多一个退款单）。
     *
     * @param subOrderId 子订单 ID（必填非空）
     * @return 退款单；不存在时返回 {@code null}（申请编排按「新建」处理，
     *         sub_order_id 唯一约束兜底并发双建）
     */
    RefundOrder findBySubOrderId(Long subOrderId);
}