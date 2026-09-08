package com.nona.inf.persistence.po.payment;

import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 支付回调留痕持久化对象（payment_callback_log 从表，global）：支付
 * 回调原文的字段化快照，append-only 追加（对账不依赖迁移成败，重复/
 * 失败回调同样留痕）。
 * <p>
 * 归属 payment_order 主键（payment_order_id，rootId 关联）；「回调
 * 原文」= callbackType/payNo/refundNo/result/channelTxnNo/amountCents
 * 六字段与渠道回调载荷同构 + occurred_at 收到时间（领域 Instant，
 * LocalDateTime UTC 字面承载）。真实渠道原始报文（raw JSON）为二期
 * 扩展位（契约演进只增不改，仅需向本表追加一列）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "payment_callback_log", indexes = {
        @Index(name = "idx_payment_callback_log_payment_order", columnList = "payment_order_id")
})
public class PaymentCallbackLogPO extends BasePO {

    /**
     * 归属支付单主键（rootId 关联）
     */
    @Column(nullable = false, name = "payment_order_id")
    private Long paymentOrderId;

    /**
     * 回调类型（渠道载荷原样：PAY/REFUND）
     */
    @Column(nullable = false, length = 16, name = "callback_type")
    @Enumerated(EnumType.STRING)
    private CallbackType callbackType;

    /**
     * 业务支付单号（渠道载荷原样）
     */
    @Column(nullable = false, length = 64, name = "pay_no")
    private String payNo;

    /**
     * 退款单号（可空：PAY 回调必空、REFUND 回调必填——类型配套约束）
     */
    @Column(length = 64, name = "refund_no")
    private String refundNo;

    /**
     * 渠道侧业务结果（SUCCESS/FAIL）
     */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private GatewayResult result;

    /**
     * 渠道流水号（留痕行缺关键字段无对账意义）
     */
    @Column(nullable = false, length = 64, name = "channel_txn_no")
    private String channelTxnNo;

    /**
     * 回调金额（分，正整数）
     */
    @Column(nullable = false, name = "amount_cents")
    private Long amountCents;

    /**
     * 回调收到时间（UTC 字面；领域 Instant 经 convertor 往返）
     */
    @Column(nullable = false, name = "occurred_at")
    private java.time.LocalDateTime occurredAt;

    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    public void setPaymentOrderId(Long paymentOrderId) {
        this.paymentOrderId = paymentOrderId;
    }

    public CallbackType getCallbackType() {
        return callbackType;
    }

    public void setCallbackType(CallbackType callbackType) {
        this.callbackType = callbackType;
    }

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public GatewayResult getResult() {
        return result;
    }

    public void setResult(GatewayResult result) {
        this.result = result;
    }

    public String getChannelTxnNo() {
        return channelTxnNo;
    }

    public void setChannelTxnNo(String channelTxnNo) {
        this.channelTxnNo = channelTxnNo;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(Long amountCents) {
        this.amountCents = amountCents;
    }

    public java.time.LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(java.time.LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}