package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 支付回调留痕从表 JPA 仓储（payment_callback_log 表，从表行，
 * append-only——本仓储只增删查，不承载字段更新）。
 *
 * @author nona9961
 */
public interface PaymentCallbackLogJpaRepository extends ListCrudRepository<PaymentCallbackLogPO, Long> {

    /**
     * 按归属支付单加载留痕行（rootId 反查，保持追加序）。
     *
     * @param paymentOrderId 支付单 ID
     * @return 留痕行列表；无留痕返回空列表
     */
    List<PaymentCallbackLogPO> findByPaymentOrderIdOrderByIdAsc(Long paymentOrderId);
}