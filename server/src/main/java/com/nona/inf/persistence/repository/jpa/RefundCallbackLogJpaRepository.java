package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 退款回调留痕从表 JPA 仓储（refund_callback_log 表，从表行，
 * append-only——本仓储只增删查，不承载字段更新）。
 *
 * @author nona9961
 */
public interface RefundCallbackLogJpaRepository extends ListCrudRepository<RefundCallbackLogPO, Long> {

    /**
     * 按归属退款单加载留痕行（rootId 反查，保持追加序）。
     *
     * @param refundOrderId 退款单 ID
     * @return 留痕行列表；无留痕返回空列表
     */
    List<RefundCallbackLogPO> findByRefundOrderIdOrderByIdAsc(Long refundOrderId);
}