package com.nona.inf.payment;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.inf.timeout.TimeoutTask;
import com.nona.inf.timeout.TimeoutTaskStore;
import com.nona.inf.timeout.TimeoutType;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 支付超时数据端口（ORDER_PAY：payment_order 表 deadline 列承载面）。
 * <p>
 * 引擎是纯调度器不感知业务表：本端口承载支付超时的「预期态」业务
 * 知识（status = PENDING_PAYMENT）与 deadline 列（timeout_at /
 * timeout_type / claimed）在 payment_order 表上的认领/清除 SQL 语义，
 * 数据面经 {@link PaymentOrderRepository} 新增契约落地（扫描面
 * findDueByStatusAndTimeoutAtBefore / claimTimeout / clearTimeoutDeadline，
 * 实现归属仓储接线 WU，本阶段不写实现）。
 * <p>
 * 元素装配：引擎扫描时以本端口 {@link #type()}（ORDER_PAY）路由——
 * 候选 id = payment_order 主键（认领/清除定位键），target = 主订单 ID
 * （{@code PaymentOrder.orderId}，支付超时关单的操作单元主单）。
 * <p>
 * 放置：业务侧包（inf.payment，与引擎包 inf.timeout 分离——引擎零业
 * 务知识，业务知识收敛于业务侧）；红阶段不注册 bean（依赖的仓储实现
 * 未接线，注册即装配错误，WU-032 决策 9 先例降级），构造器直接装配。
 *
 * @author nona9961
 */
@Component
public class PayTimeoutStore implements TimeoutTaskStore<Long> {

    /**
     * 支付超时预期态（本端口承载的业务知识：仅待支付单参与扫描与认领）。
     */
    private static final PaymentOrderStatus EXPECTED_STATUS = PaymentOrderStatus.PENDING_PAYMENT;

    /**
     * 支付单仓储（数据面：扫描/认领/清除契约的落地锚点）。
     */
    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * 构造支付超时数据端口。
     *
     * @param paymentOrderRepository 支付单仓储（必填）
     */
    public PayTimeoutStore(PaymentOrderRepository paymentOrderRepository) {
        this.paymentOrderRepository = paymentOrderRepository;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimeoutType type() {
        return TimeoutType.ORDER_PAY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TimeoutTask<Long>> findDue(Instant now, int limit) {
        return paymentOrderRepository
                .findDueByStatusAndTimeoutAtBefore(EXPECTED_STATUS, now, limit)
                .stream()
                .map(paymentOrder -> new TimeoutTask<>(
                        paymentOrder.getId(), paymentOrder.getOrderId()))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean claim(TimeoutTask<Long> task) {
        return paymentOrderRepository.claimTimeout(task.id(), EXPECTED_STATUS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearDeadline(TimeoutTask<Long> task) {
        paymentOrderRepository.clearTimeoutDeadline(task.id());
    }
}