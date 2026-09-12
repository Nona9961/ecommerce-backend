package com.nona.inf.persistence.converters;

import com.nona.domain.payment.entity.RefundOrder;
import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import com.nona.inf.persistence.po.payment.RefundOrderPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 退款单聚合根 ↔ 退款单 PO 转换器（主表 refund_order，global；从表
 * refund_callback_log 留痕行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 退款单主键，refundNo 唯一业务单号，
 * sub_order_id 一子单一退款单锚点）；shippedAtApply（申请时刻已发货
 * 快照）与 reason（可空申请原因）直映射；status/channelRefundTxnNo 为
 * 持久化变更位；channel_refund_txn_no 不建唯一约束（FAILED 重试覆盖
 * 更新流水——javadoc 契约）。other 参数为从表留痕行集合（读路径由
 * 仓储 getOther 提供）。
 *
 * @author nona9961
 */
@Component
public class RefundOrderConvertor
        extends AbstractConvertor<RefundOrder, RefundOrderPO, List<RefundCallbackLogPO>> {

    /**
     * 留痕行转换器
     */
    private final RefundCallbackRecordConvertor callbackConvertor;

    /**
     * 构造退款单转换器。
     *
     * @param callbackConvertor 留痕行转换器
     */
    public RefundOrderConvertor(RefundCallbackRecordConvertor callbackConvertor) {
        this.callbackConvertor = callbackConvertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected RefundOrderPO safedConvertToPO(RefundOrder root) {
        final RefundOrderPO po = new RefundOrderPO();
        po.setId(root.getId());
        po.setRefundNo(root.getRefundNo());
        po.setPayNo(root.getPayNo());
        po.setSubOrderId(root.getSubOrderId());
        po.setAmount(root.getAmount());
        po.setShippedAtApply(root.isShippedAtApply());
        po.setReason(root.getReason());
        po.setStatus(root.getStatus());
        po.setChannelRefundTxnNo(root.getChannelRefundTxnNo());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected RefundOrder safedConvertToRoot(RefundOrderPO po,
                                              List<RefundCallbackLogPO> callbackPOs) {
        final List<RefundCallbackLogPO> rows = callbackPOs == null ? List.of() : callbackPOs;
        return new RefundOrder(po.getId(), po.getRefundNo(), po.getPayNo(),
                po.getSubOrderId(), po.getAmount(),
                Boolean.TRUE.equals(po.getShippedAtApply()), po.getReason(),
                po.getStatus(), po.getChannelRefundTxnNo(),
                rows.stream().map(callbackConvertor::toDomain).toList());
    }
}