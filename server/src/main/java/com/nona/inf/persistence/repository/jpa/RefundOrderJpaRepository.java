package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.payment.RefundOrderPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

/**
 * 退款单主表 JPA 仓储（refund_order 表，主表行）。
 *
 * @author nona9961
 */
public interface RefundOrderJpaRepository extends ListCrudRepository<RefundOrderPO, Long> {

    /**
     * 按退款单号装载（退款回调处理消费面）。
     *
     * @param refundNo 退款单号
     * @return 主表行；不存在返回空
     */
    Optional<RefundOrderPO> findByRefundNo(String refundNo);

    /**
     * 按操作单元子单装载（申请防重 + 发货超时幂等短路消费面，唯一）。
     *
     * @param subOrderId 子订单 ID
     * @return 主表行；不存在返回空
     */
    Optional<RefundOrderPO> findBySubOrderId(Long subOrderId);
}