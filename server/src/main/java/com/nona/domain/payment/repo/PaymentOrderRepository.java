package com.nona.domain.payment.repo;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.persistence.BaseRepository;

import java.time.Instant;
import java.util.List;

/**
 * 支付单仓储接口：支付单聚合根的持久化契约（payment_order 主表 +
 * payment_callback_log 从表，买家维度 global）。
 * <p>
 * 实现在基础设施层（继承 DifferRepository：主表快照追踪 + 变更集驱动
 * 落库——主表唯一行；从表留痕集合经 getOther 装载/追加插行，Cart/
 * CartItem 判例同构）；领域层只依赖本契约，不感知 JPA。聚合根主表以
 * 独立主键（paymentOrderId）承载身份。
 * <p>
 * 唯一约束契约（TD-11 防线一 + 一对一主单的 DB 物理面，DDL 落地时按
 * 本 javadoc 核对）：
 * <ul>
 *     <li>{@code pay_no} 唯一（业务单号，TD-13）；</li>
 *     <li>{@code order_id} 唯一（支付单与主单一对一——并发同主单双创建
 *         的最终防线，正常路径由发起支付编排「复用/拒绝」判定守护）；</li>
 *     <li>{@code channel_txn_no} 唯一（防线一「重复回调插入即失败」——
 *         并发窗口的物理兜底；未回调为 NULL 时唯一约束允许多行）；</li>
 *     <li>(status, timeout_at) 复合索引（支付超时引擎扫描面）。</li>
 * </ul>
 * 查询契约遵循「契约演进只增不改」：本阶段冻结按 payNo 与按 orderId
 * 两装载（回调处理 / 发起支付复用判定消费面）；支付单列表/详情等查询
 * 面随消费编排 WU 扩展。
 *
 * @author nona9961
 */
public interface PaymentOrderRepository extends BaseRepository<Long, PaymentOrder> {

    /**
     * 按支付单号装载（回调处理消费面：handlePayCallback 装载锚点）。
     *
     * @param payNo 支付单号（必填非空）
     * @return 支付单；不存在时返回 {@code null}（调用方按
     *         {@code payment.not_found} 呈现，不产生孤儿处理路径）
     */
    PaymentOrder findByPayNo(String payNo);

    /**
     * 按关联主单装载（发起支付复用判定消费面：同主单唯一支付单）。
     *
     * @param orderId 主订单 ID（必填非空）
     * @return 支付单；不存在时返回 {@code null}（发起支付编排按「补建」
     *         处理，order_id 唯一约束兜底并发双建）
     */
    PaymentOrder findByOrderId(Long orderId);

    /**
     * 支付超时扫描面：返回状态处于预期态（PENDING_PAYMENT）且截止时间
     * 已到期（timeout_at &lt;= now，含等号边界）的候选，按截止时间升序
     * 至多返回 limit 条（走 (status, timeout_at) 复合索引）。
     * <p>
     * 条件语义（接线 WU 无漂移落地依据）——{@code status = 预期态 AND
     * timeout_at <= now}；不含 claimed 过滤（引擎事务语义保证无死认领
     * 行，见引擎契约）；调用方（支付超时数据端口）传入预期态与扫描
     * 时刻，本接口不写死任何状态值。
     *
     * @param status 预期态（支付超时时 = PENDING_PAYMENT，由调用方传入）
     * @param now    扫描时刻（到期判定边界，含等号）
     * @param limit  单轮扫描上限（引擎 SCAN_LIMIT 语义透传）
     * @return 到期候选；无到期返回空列表
     */
    List<PaymentOrder> findDueByStatusAndTimeoutAtBefore(PaymentOrderStatus status,
                                                         Instant now, int limit);

    /**
     * 乐观锁认领（支付超时数据端口 claim 的条件 UPDATE 落地面）：等价于
     * {@code UPDATE ... SET claimed = 1 WHERE id = ? AND claimed = 0
     * AND status = expectedStatus}。
     * <p>
     * 认领时刻复查状态条件：候选可能在扫描后被并发路径迁移（买家主动
     * 支付/取消已同步清除截止时间、或他方调度器已抢先认领），条件不满足
     * 即认领失败。影响行数 1 → true，0 → false。
     *
     * @param id             候选主键（payment_order 主键）
     * @param expectedStatus 认领复查的预期态（支付超时时 = PENDING_PAYMENT）
     * @return true = 认领成功；false = 已被认领或状态已迁移
     */
    boolean claimTimeout(Long id, PaymentOrderStatus expectedStatus);

    /**
     * 处理成功后清除截止时间（支付超时数据端口 clearDeadline 的落地面）：
     * 等价于 {@code UPDATE ... SET timeout_at = NULL, timeout_type = NULL,
     * claimed = 0 WHERE id = ?}——该行从此不再被扫描命中；成功路径同时
     * 清除认领位，不残留处理痕迹（无条件幂等：目标不存在/已清除均为
     * 无操作成功）。
     *
     * @param id 候选主键（payment_order 主键）
     */
    void clearTimeoutDeadline(Long id);
}