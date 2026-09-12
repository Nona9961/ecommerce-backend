package com.nona.inf.persistence.converters;

import com.nona.domain.payment.entity.RefundCallbackRecord;
import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 退款回调留痕实体 ↔ 退款回调留痕 PO 转换器（refund_callback_log 从表
 * 行映射，append-only）。
 * <p>
 * 与支付留痕同构（六字段逐列 + occurred_at Instant UTC 字面往返，
 * D4 定案）；refundNo 可空直透（PAY 回调不携带，领域构造器以类型/
 * 字段配套约束守卫）。PO 行 id 与领域 id 一一对应。
 *
 * @author nona9961
 */
@Component
public class RefundCallbackRecordConvertor
        implements PoConverter<RefundCallbackRecord, RefundCallbackLogPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<RefundCallbackRecord> domainClass() {
        return RefundCallbackRecord.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<RefundCallbackLogPO> poClass() {
        return RefundCallbackLogPO.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RefundCallbackLogPO toPO(RefundCallbackRecord domain) {
        final RefundCallbackLogPO po = new RefundCallbackLogPO();
        po.setId(domain.getId());
        po.setRefundOrderId(domain.getRefundOrderId());
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
    public RefundCallbackRecord toDomain(RefundCallbackLogPO po) {
        return new RefundCallbackRecord(po.getId(), po.getRefundOrderId(),
                po.getCallbackType(), po.getPayNo(), po.getRefundNo(), po.getResult(),
                po.getChannelTxnNo(), po.getAmountCents(),
                po.getOccurredAt() == null ? null
                        : po.getOccurredAt().atOffset(ZoneOffset.UTC).toInstant());
    }
}