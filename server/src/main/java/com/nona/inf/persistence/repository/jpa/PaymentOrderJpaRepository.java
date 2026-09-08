package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import org.springframework.data.repository.ListCrudRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 支付单主表 JPA 仓储（payment_order 表，主表行）。
 *
 * @author nona9961
 */
public interface PaymentOrderJpaRepository extends ListCrudRepository<PaymentOrderPO, Long> {

    /**
     * 按支付单号装载（回调处理消费面）。
     *
     * @param payNo 支付单号
     * @return 主表行；不存在返回空
     */
    Optional<PaymentOrderPO> findByPayNo(String payNo);

    /**
     * 按关联主单装载（发起支付复用判定消费面，一对一）。
     *
     * @param orderId 主订单 ID
     * @return 主表行；不存在返回空
     */
    Optional<PaymentOrderPO> findByOrderId(Long orderId);

    /**
     * 支付超时扫描面（走 (status, timeout_at) 复合索引）：状态为预期态
     * 且截止时间已到期（含等号边界）的候选，按截止时间升序至多 limit 条。
     *
     * @param status    预期态（支付超时时 = PENDING_PAYMENT）
     * @param timeoutAt 扫描时刻（含等号）
     * @param limit     单轮扫描上限
     * @return 到期候选；无到期返回空列表
     */
    List<PaymentOrderPO> findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
            PaymentOrderStatus status, LocalDateTime timeoutAt, org.springframework.data.domain.Limit limit);
}