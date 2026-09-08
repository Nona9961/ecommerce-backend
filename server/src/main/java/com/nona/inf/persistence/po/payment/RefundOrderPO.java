package com.nona.inf.persistence.po.payment;

import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 退款单聚合根持久化对象（refund_order 表，global）聚合根主表，买家
 * 维度数据全局可见。
 * <p>
 * 主键 id = 退款单独立主键（Snowflake，全局唯一）。唯一约束契约（随
 * 仓储接口 javadoc 核对）：refund_no 唯一（TD-13 业务退款单号：RF +
 * 日期 + snowflake 后段）；sub_order_id 唯一（一子单一生至多一个退款
 * 单——重复申请防重复的 DB 物理兜底，FAILED 可重试复用同一单）；
 * channel_refund_txn_no <b>不建</b>唯一约束（FAILED 重试受理覆盖更新
 * 流水，唯一约束会拦覆盖——流水一致防线由聚合方法守卫承载）。不建
 * 超时索引（退款无超时调度面）。从表 refund_callback_log 以
 * refund_order_id（rootId）关联。
 *
 * @author nona9961
 */
@Entity
@Table(name = "refund_order", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_order_refund_no", columnNames = "refund_no"),
        @UniqueConstraint(name = "uk_refund_order_sub_order_id", columnNames = "sub_order_id")
})
public class RefundOrderPO extends BasePO {

    /**
     * 退款单号（TD-13 业务退款单号，唯一）
     */
    @Column(nullable = false, length = 64, name = "refund_no")
    private String refundNo;

    /**
     * 关联支付单号（原交易定位，跨聚合引用 ID）
     */
    @Column(nullable = false, length = 64, name = "pay_no")
    private String payNo;

    /**
     * 操作单元子单 ID（唯一——一子单一退款单）
     */
    @Column(nullable = false, name = "sub_order_id")
    private Long subOrderId;

    /**
     * 退款金额（分，= 子单实付，创建时固化）
     */
    @Column(nullable = false)
    private Long amount;

    /**
     * 申请时是否已发货（发货时点快照：未发货退款走库存回补路径）
     */
    @Column(nullable = false, name = "shipped_at_apply")
    private Boolean shippedAtApply;

    /**
     * 申请原因（买家输入，可空——不承载语义）
     */
    @Column(length = 255)
    private String reason;

    /**
     * 退款单状态（状态机唯一可变位之一，迁移经聚合方法）
     */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private RefundOrderStatus status;

    /**
     * 渠道退款流水号（可变位之二；可空=尚未受理；无唯一约束——重试
     * 受理覆盖更新）
     */
    @Column(length = 64, name = "channel_refund_txn_no")
    private String channelRefundTxnNo;

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
    }

    public Long getSubOrderId() {
        return subOrderId;
    }

    public void setSubOrderId(Long subOrderId) {
        this.subOrderId = subOrderId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Boolean getShippedAtApply() {
        return shippedAtApply;
    }

    public void setShippedAtApply(Boolean shippedAtApply) {
        this.shippedAtApply = shippedAtApply;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public RefundOrderStatus getStatus() {
        return status;
    }

    public void setStatus(RefundOrderStatus status) {
        this.status = status;
    }

    public String getChannelRefundTxnNo() {
        return channelRefundTxnNo;
    }

    public void setChannelRefundTxnNo(String channelRefundTxnNo) {
        this.channelRefundTxnNo = channelRefundTxnNo;
    }
}