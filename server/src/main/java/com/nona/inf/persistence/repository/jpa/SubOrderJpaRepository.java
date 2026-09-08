package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.inf.persistence.po.order.SubOrderPO;
import org.springframework.data.repository.ListCrudRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 子订单主表 JPA 仓储（sub_order 表，tenant=shopId 主表行）。
 *
 * @author nona9961
 */
public interface SubOrderJpaRepository extends ListCrudRepository<SubOrderPO, Long> {

    /**
     * 按归属主订单加载子单行（主单维度反查，保持创建序）。
     *
     * @param masterOrderId 主订单 ID
     * @return 子单行列表；无子单返回空列表
     */
    List<SubOrderPO> findByMasterOrderIdOrderByIdAsc(Long masterOrderId);

    /**
     * 超时扫描面（走 (status, timeout_at) 复合索引）：状态为预期态且
     * 截止时间已到期（含等号边界）的候选，按截止时间升序至多 limit 条。
     *
     * @param status   预期态（发货超时 = PAID；收货超时 = SHIPPED）
     * @param timeoutAt 扫描时刻（含等号）
     * @param limit    单轮扫描上限
     * @return 到期候选；无到期返回空列表
     */
    List<SubOrderPO> findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
            SubOrderStatus status, LocalDateTime timeoutAt, org.springframework.data.domain.Limit limit);
}