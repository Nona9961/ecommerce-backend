package com.nona.inf.persistence.po.payment;

import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.inf.persistence.po.BasePO;
import com.nona.inf.timeout.TimeoutType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 支付单聚合根持久化对象（payment_order 表，global）聚合根主表，买家
 * 维度数据全局可见。
 * <p>
 * 主键 id = 支付单独立主键（Snowflake，全局唯一）。唯一约束契约
 * （防线一 + 一对一主单的 DB 物理面，随仓储接口 javadoc 核对）：
 * pay_no 唯一（业务单号：PAY + 日期 + snowflake 后段）；order_id
 * 唯一（支付单与主单一对一）；channel_txn_no 唯一（重复回调防线——
 * 未回调为 NULL 时唯一约束允许多行）。(status, timeout_at) 复合索引为
 * 支付超时引擎扫描面（findDueByStatusAndTimeoutAtBefore 契约）。
 * <p>
 * 超时 SQL 面列同 sub_order 先例：timeout_type（ORDER_PAY，可空）/
 * claimed（非空默认 false）——claim/clear 落地面由仓储实现侧 SQL
 * 维护，转换器不负责。timeout_at 领域字段为 Instant，PO 列
 * 以 LocalDateTime 承载（datetime(6)，UTC 字面往返）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "payment_order", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order_pay_no", columnNames = "pay_no"),
        @UniqueConstraint(name = "uk_payment_order_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_order_channel_txn_no", columnNames = "channel_txn_no")
}, indexes = {
        @Index(name = "idx_payment_order_status_timeout", columnList = "status, timeout_at")
})
public class PaymentOrderPO extends BasePO {

    /**
     * 支付单号（业务单号，唯一）
     */
    @Column(nullable = false, length = 64, name = "pay_no")
    private String payNo;

    /**
     * 关联主单 ID（引用 ID 协作，唯一——一对一主单）
     */
    @Column(nullable = false, name = "order_id")
    private Long orderId;

    /**
     * 支付金额（分，= 主单实付）
     */
    @Column(nullable = false)
    private Long amount;

    /**
     * 支付渠道（当前唯一实现 MOCK，字段保留真实渠道扩展位）
     */
    @Column(nullable = false, length = 32)
    private String channel;

    /**
     * 支付超时截止时间（可空：未到期为值、clear 后为 NULL；UTC 字面）
     */
    @Column(name = "timeout_at")
    private java.time.LocalDateTime timeoutAt;

    /**
     * 支付单状态（状态机唯一可变位之一，迁移经聚合方法）
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private PaymentOrderStatus status;

    /**
     * 渠道流水号（唯一可变位之二；可空=尚未回调，NULL 多行允许）
     */
    @Column(length = 64, name = "channel_txn_no")
    private String channelTxnNo;

    /**
     * 超时类型（SQL 面列：ORDER_PAY，可空）
     */
    @Column(length = 32, name = "timeout_type")
    @Enumerated(EnumType.STRING)
    private TimeoutType timeoutType;

    /**
     * 超时认领位（SQL 面列：claim 乐观锁语义，非空默认 false）
     */
    @Column(nullable = false, name = "claimed")
    private Boolean claimed = Boolean.FALSE;

    public String getPayNo() {
        return payNo;
    }

    public void setPayNo(String payNo) {
        this.payNo = payNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public java.time.LocalDateTime getTimeoutAt() {
        return timeoutAt;
    }

    public void setTimeoutAt(java.time.LocalDateTime timeoutAt) {
        this.timeoutAt = timeoutAt;
    }

    public PaymentOrderStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentOrderStatus status) {
        this.status = status;
    }

    public String getChannelTxnNo() {
        return channelTxnNo;
    }

    public void setChannelTxnNo(String channelTxnNo) {
        this.channelTxnNo = channelTxnNo;
    }

    public TimeoutType getTimeoutType() {
        return timeoutType;
    }

    public void setTimeoutType(TimeoutType timeoutType) {
        this.timeoutType = timeoutType;
    }

    public Boolean getClaimed() {
        return claimed;
    }

    public void setClaimed(Boolean claimed) {
        this.claimed = claimed;
    }
}