package com.nona.inf.persistence.po.order;

import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import com.nona.inf.timeout.TimeoutType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 子订单聚合根持久化对象（sub_order 表，tenant=shopId）：店铺维度履约
 * 单元的聚合根主表（TenantScopedBasePO——tenant_id 隔离列 + shop_id
 * 业务关联列双列先例见 ProductPO）。
 * <p>
 * 主键 id = 子订单独立主键（Snowflake，全局唯一）；master_order_id
 * 业务关联列（非唯一，主单下 N 子单）；sub_order_no 业务单号唯一
 * （TD-13：SUB + 日期 + snowflake 后段）；地址六列 + 金额四列为快照
 * 冗余列（创建时定型）；status 履约状态（状态机迁移收敛在聚合方法）；
 * waybill_id 发货定型（可空，跨域引用 ID）。从表 order_item 以
 * sub_order_id（rootId）关联。
 * <p>
 * 超时 SQL 面三列（claim/clear 防线的数据面，领域实体不承载）：
 * timeout_at（截止时间，扫描面列，可空——未到期/已清除为 NULL）、
 * timeout_type（超时类型 ORDER_SHIP/ORDER_RECEIVE，可空）、claimed
 * （认领位，非空默认 false）——(status, timeout_at) 复合索引为履约
 * 超时引擎扫描面（findDueByStatusAndTimeoutAtBefore 契约）。三列由
 * 仓储实现侧 SQL（WU-55 claim/clear 落地面）维护，转换器不负责。
 *
 * @author nona9961
 */
@Entity
@Table(name = "sub_order", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sub_order_sub_order_no", columnNames = "sub_order_no")
}, indexes = {
        @Index(name = "idx_sub_order_status_timeout", columnList = "status, timeout_at"),
        @Index(name = "idx_sub_order_shop", columnList = "shop_id")
})
public class SubOrderPO extends TenantScopedBasePO {

    /**
     * 归属主订单 ID（业务关联列，非唯一）
     */
    @Column(nullable = false, name = "master_order_id")
    private Long masterOrderId;

    /**
     * 归属店铺 ID（业务关联列，tenant=shopId 语义的业务投影）
     */
    @Column(nullable = false, name = "shop_id")
    private Long shopId;

    /**
     * 子订单号（TD-13 业务单号，唯一）
     */
    @Column(nullable = false, length = 64, name = "sub_order_no")
    private String subOrderNo;

    /**
     * 收货人快照（AddressSnapshot 扁平列）
     */
    @Column(nullable = false, length = 64)
    private String recipient;

    /**
     * 联系电话快照（AddressSnapshot 扁平列）
     */
    @Column(nullable = false, length = 32)
    private String phone;

    /**
     * 省快照（AddressSnapshot 扁平列）
     */
    @Column(nullable = false, length = 32)
    private String province;

    /**
     * 市快照（AddressSnapshot 扁平列）
     */
    @Column(nullable = false, length = 32)
    private String city;

    /**
     * 区/县快照（AddressSnapshot 扁平列）
     */
    @Column(nullable = false, length = 32)
    private String district;

    /**
     * 详细地址快照（AddressSnapshot 扁平列）
     */
    @Column(nullable = false, length = 255)
    private String detail;

    /**
     * 商品总额快照（分，AmountDetail 扁平列）
     */
    @Column(nullable = false, name = "goods_amount")
    private Long goodsAmount;

    /**
     * 运费快照（分，AmountDetail 扁平列）
     */
    @Column(nullable = false, name = "freight_amount")
    private Long freightAmount;

    /**
     * 优惠快照（分，AmountDetail 扁平列）
     */
    @Column(nullable = false)
    private Long discount;

    /**
     * 实付金额快照（分，AmountDetail 扁平列）
     */
    @Column(nullable = false, name = "paid_amount")
    private Long paidAmount;

    /**
     * 履约状态（状态机唯一可变位，迁移经聚合方法）
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private SubOrderStatus status;

    /**
     * 运单 ID（可空：发货经 markShipped 定型后非空；跨域引用 ID）
     */
    @Column(name = "waybill_id")
    private Long waybillId;

    /**
     * 超时截止时间（SQL 面列：超时引擎扫描/清除，可空）
     */
    @Column(name = "timeout_at")
    private java.time.LocalDateTime timeoutAt;

    /**
     * 超时类型（SQL 面列：OrderShip/OrderReceive，可空）
     */
    @Column(length = 32, name = "timeout_type")
    @Enumerated(EnumType.STRING)
    private TimeoutType timeoutType;

    /**
     * 超时认领位（SQL 面列：claim 乐观锁语义，非空默认 false）
     */
    @Column(nullable = false, name = "claimed")
    private Boolean claimed = Boolean.FALSE;

    public Long getMasterOrderId() {
        return masterOrderId;
    }

    public void setMasterOrderId(Long masterOrderId) {
        this.masterOrderId = masterOrderId;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public String getSubOrderNo() {
        return subOrderNo;
    }

    public void setSubOrderNo(String subOrderNo) {
        this.subOrderNo = subOrderNo;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Long getGoodsAmount() {
        return goodsAmount;
    }

    public void setGoodsAmount(Long goodsAmount) {
        this.goodsAmount = goodsAmount;
    }

    public Long getFreightAmount() {
        return freightAmount;
    }

    public void setFreightAmount(Long freightAmount) {
        this.freightAmount = freightAmount;
    }

    public Long getDiscount() {
        return discount;
    }

    public void setDiscount(Long discount) {
        this.discount = discount;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(Long paidAmount) {
        this.paidAmount = paidAmount;
    }

    public SubOrderStatus getStatus() {
        return status;
    }

    public void setStatus(SubOrderStatus status) {
        this.status = status;
    }

    public Long getWaybillId() {
        return waybillId;
    }

    public void setWaybillId(Long waybillId) {
        this.waybillId = waybillId;
    }

    public java.time.LocalDateTime getTimeoutAt() {
        return timeoutAt;
    }

    public void setTimeoutAt(java.time.LocalDateTime timeoutAt) {
        this.timeoutAt = timeoutAt;
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