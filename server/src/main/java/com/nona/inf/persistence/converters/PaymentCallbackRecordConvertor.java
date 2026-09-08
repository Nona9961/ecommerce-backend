package com.nona.inf.persistence.converters;

import com.nona.domain.payment.entity.PaymentCallbackRecord;
import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 支付回调留痕实体 ↔ 支付回调留痕 PO 转换器（payment_callback_log 从表
 * 行映射，append-only）。
 * <p>
 * 六字段逐列映射；occurred_at（收到时间）领域 Instant 以 UTC 字面往返
 * （D4 定案）；refundNo 可空直透（PAY 回调不携带，领域构造器以类型/
 * 字段配套约束守卫）。PO 行 id 与领域 id 一一对应（留痕为标识实体）。
 *
 * @author nona9961
 */
@Component
public class PaymentCallbackRecordConvertor
        implements PoConverter<PaymentCallbackRecord, PaymentCallbackLogPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<PaymentCallbackRecord> domainClass() {
        return PaymentCallbackRecord.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<PaymentCallbackLogPO> poClass() {
        return PaymentCallbackLogPO.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PaymentCallbackLogPO toPO(PaymentCallbackRecord domain) {
        final PaymentCallbackLogPO po = new PaymentCallbackLogPO();
        po.setId(domain.getId());
        po.setPaymentOrderId(domain.getPaymentOrderId());
        po.setCallbackType(domain.getCallbackType());
        po.setPayNo(domain.getPayNo());
        po.setRefundNo(domain.getRefundNo());
        po.setResult(domain.getResult());
        po.setChannelTxnNo(domain.getChannelTxnNo());
        po.setAmountCents(domain.getAmountCents());
        po.setOccurredAt(LocalDateTime.ofInstant(domain.getOccurredAt(), ZoneOffset.UTC));
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PaymentCallbackRecord toDomain(PaymentCallbackLogPO po) {
        return new PaymentCallbackRecord(po.getId(), po.getPaymentOrderId(),
                po.getCallbackType(), po.getPayNo(), po.getRefundNo(), po.getResult(),
                po.getChannelTxnNo(), po.getAmountCents(),
                po.getOccurredAt() == null ? null
                        : po.getOccurredAt().atOffset(ZoneOffset.UTC).toInstant());
    }
}