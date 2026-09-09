package com.nona.inf.order;

import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.inf.timeout.TimeoutTask;
import com.nona.inf.timeout.TimeoutTaskStore;
import com.nona.inf.timeout.TimeoutType;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 收货超时数据端口（ORDER_RECEIVE：sub_order 表 deadline 列承载面）。
 * <p>
 * 引擎是纯调度器不感知业务表：本端口承载收货超时的「预期态」业务
 * 知识（status = SHIPPED——已发货未确认收货）与 deadline 列
 * （timeout_at / timeout_type / claimed）在 sub_order 表上的认领/清除
 * SQL 语义，数据面经 {@link SubOrderRepository} 新增契约落地（扫描面
 * findDueByStatusAndTimeoutAtBefore / claimTimeout / clearTimeoutDeadline，
 * 实现归属仓储接线 WU，本阶段不写实现）。
 * <p>
 * 元素装配：引擎扫描时以本端口 {@link #type()}（ORDER_RECEIVE）路由——
 * 候选 id = sub_order 主键（认领/清除定位键），target = 子订单 ID
 * （收货超时自动完成的操作单元子单；id 与 target 同值）。
 * <p>
 * 放置：业务侧包（inf.order，与引擎包 inf.timeout 分离——引擎零业务
 * 知识，业务知识收敛于业务侧）；红阶段不注册 bean（依赖的仓储实现
 * 未接线，注册即装配错误，WU-032 决策 9 先例降级），构造器直接装配。
 *
 * @author nona9961
 */
@Component
public class ReceiveTimeoutStore implements TimeoutTaskStore<Long> {

    /**
     * 收货超时预期态（本端口承载的业务知识：仅已发货未确认收货子单
     * 参与扫描与认领）。
     */
    private static final SubOrderStatus EXPECTED_STATUS = SubOrderStatus.SHIPPED;

    /**
     * 子订单仓储（数据面：扫描/认领/清除契约的落地锚点）。
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 构造收货超时数据端口。
     *
     * @param subOrderRepository 子订单仓储（必填）
     */
    public ReceiveTimeoutStore(SubOrderRepository subOrderRepository) {
        this.subOrderRepository = subOrderRepository;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimeoutType type() {
        return TimeoutType.ORDER_RECEIVE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TimeoutTask<Long>> findDue(Instant now, int limit) {
        return subOrderRepository
                .findDueByStatusAndTimeoutAtBefore(EXPECTED_STATUS, now, limit)
                .stream()
                .map(subOrder -> new TimeoutTask<>(subOrder.getId(), subOrder.getId()))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean claim(TimeoutTask<Long> task) {
        return subOrderRepository.claimTimeout(task.id(), EXPECTED_STATUS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearDeadline(TimeoutTask<Long> task) {
        subOrderRepository.clearTimeoutDeadline(task.id());
    }
}