package com.nona.inf.persistence.po.order;

import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 主订单聚合根持久化对象（master_order 表，global）：聚合根主表，买家
 * 维度数据全局可见。
 * <p>
 * 主键 id = 主订单独立主键（Snowflake，全局唯一）；order_no 业务单号唯一
 * （TD-13：ORD + 日期 + snowflake 后段）；buyer_id 业务关联列；地址六列
 * （recipient/phone/province/city/district/detail）+ 金额四列
 * （goods_amount/freight_amount/discount/paid_amount）为快照冗余列
 * （AddressSnapshot/AmountDetail 不可变 VO 扁平化，B7.6 创建时定型）；
 * status 为派生态（子单投影派生刷新）。主表唯一行、无从表——子单 id
 * 集合经 sub_order.master_order_id 反查装载（引用 ID 协作，不建外键）。
 * 主表行由 {@code MasterOrderConvertor} 与领域实体互转；整体状态枚举
 * 以字符串持久化（@Enumerated(STRING)，V1 同款先例）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "master_order", uniqueConstraints = {
        @UniqueConstraint(name = "uk_master_order_order_no", columnNames = "order_no")
})
public class MasterOrderPO extends BasePO {

    /**
     * 订单号（TD-13 业务单号，唯一）
     */
    @Column(nullable = false, length = 64, name = "order_no")
    private String orderNo;

    /**
     * 归属买家账号 ID（业务关联列）
     */
    @Column(nullable = false, name = "buyer_id")
    private Long buyerId;

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
     * 整体状态（派生态：子单状态投影经 MasterOrderStatusDeriver 刷新）
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private MasterOrderStatus status;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
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

    public MasterOrderStatus getStatus() {
        return status;
    }

    public void setStatus(MasterOrderStatus status) {
        this.status = status;
    }
}