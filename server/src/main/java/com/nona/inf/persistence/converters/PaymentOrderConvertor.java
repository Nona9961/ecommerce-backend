package com.nona.inf.persistence.converters;

import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 支付单聚合根 ↔ 支付单 PO 转换器（主表 payment_order，global；从表
 * payment_callback_log 留痕行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 支付单主键，payNo 唯一业务单号，order_id
 * 一对一锚点，channel_txn_no 唯一渠道流水 = 重复回调 DB 防线）；timeout_at
 * 领域 Instant 以 UTC 字面往返（datetime(6) 无时区字面语义，防服务器
 * 时区漂移改变已存值——D4 定案）；status/channelTxnNo 为持久化变更位。
 * 超时 SQL 面两列（timeout_type/claimed）为仓储实现侧维护，转换器不
 * 读写（D11）。other 参数为从表留痕行集合（读路径由仓储 getOther
 * 提供）。
 *
 * @author nona9961
 */
@Component
public class PaymentOrderConvertor
        extends AbstractConvertor<PaymentOrder, PaymentOrderPO, List<PaymentCallbackLogPO>> {

    /**
     * 留痕行转换器
     */
    private final PaymentCallbackRecordConvertor callbackConvertor;

    /**
     * 构造支付单转换器。
     *
     * @param callbackConvertor 留痕行转换器
     */
    public PaymentOrderConvertor(PaymentCallbackRecordConvertor callbackConvertor) {
        this.callbackConvertor = callbackConvertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected PaymentOrderPO safedConvertToPO(PaymentOrder root) {
        final PaymentOrderPO po = new PaymentOrderPO();
        po.setId(root.getId());
        po.setPayNo(root.getPayNo());
        po.setOrderId(root.getOrderId());
        po.setAmount(root.getAmount());
        po.setChannel(root.getChannel());
        po.setTimeoutAt(LocalDateTime.ofInstant(root.getTimeoutAt(), ZoneOffset.UTC));
        po.setStatus(root.getStatus());
        po.setChannelTxnNo(root.getChannelTxnNo());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected PaymentOrder safedConvertToRoot(PaymentOrderPO po,
                                               List<PaymentCallbackLogPO> callbackPOs) {
        final List<PaymentCallbackLogPO> rows = callbackPOs == null ? List.of() : callbackPOs;
        return new PaymentOrder(po.getId(), po.getPayNo(), po.getOrderId(),
                po.getAmount(), po.getChannel(),
                po.getTimeoutAt() == null ? null
                        : po.getTimeoutAt().atOffset(ZoneOffset.UTC).toInstant(),
                po.getStatus(), po.getChannelTxnNo(),
                rows.stream().map(callbackConvertor::toDomain).toList());
    }
}