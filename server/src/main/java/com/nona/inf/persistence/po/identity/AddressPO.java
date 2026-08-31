package com.nona.inf.persistence.po.identity;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 买家收货地址持久化对象（address 表，global）：地址簿的一行，
 * 归属买家维度（account_id），默认标记（is_default）同买家至多一个
 * （唯一性由应用层买家维度锁 + 迁移式更新保证，见 AddressBookRepository）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "address", indexes = {
        @Index(name = "idx_address_account", columnList = "account_id")
})
public class AddressPO extends BasePO {

    /**
     * 归属买家账号 ID
     */
    @Column(nullable = false, name = "account_id")
    private Long accountId;

    /**
     * 收件人
     */
    @Column(nullable = false, length = 64)
    private String recipient;

    /**
     * 联系电话
     */
    @Column(nullable = false, length = 32)
    private String phone;

    /**
     * 省份
     */
    @Column(nullable = false, length = 32)
    private String province;

    /**
     * 城市
     */
    @Column(nullable = false, length = 32)
    private String city;

    /**
     * 区县
     */
    @Column(nullable = false, length = 32)
    private String district;

    /**
     * 详细地址
     */
    @Column(nullable = false, length = 255)
    private String detail;

    /**
     * 是否默认地址（同买家至多一个为 true）
     */
    @Column(nullable = false)
    private Boolean isDefault;

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置归属买家账号 ID。
     *
     * @param accountId 买家账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /**
     * 收件人。
     *
     * @return 收件人
     */
    public String getRecipient() {
        return recipient;
    }

    /**
     * 设置收件人。
     *
     * @param recipient 收件人
     */
    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    /**
     * 联系电话。
     *
     * @return 联系电话
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 设置联系电话。
     *
     * @param phone 联系电话
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    /**
     * 省份。
     *
     * @return 省份
     */
    public String getProvince() {
        return province;
    }

    /**
     * 设置省份。
     *
     * @param province 省份
     */
    public void setProvince(String province) {
        this.province = province;
    }

    /**
     * 城市。
     *
     * @return 城市
     */
    public String getCity() {
        return city;
    }

    /**
     * 设置城市。
     *
     * @param city 城市
     */
    public void setCity(String city) {
        this.city = city;
    }

    /**
     * 区县。
     *
     * @return 区县
     */
    public String getDistrict() {
        return district;
    }

    /**
     * 设置区县。
     *
     * @param district 区县
     */
    public void setDistrict(String district) {
        this.district = district;
    }

    /**
     * 详细地址。
     *
     * @return 详细地址
     */
    public String getDetail() {
        return detail;
    }

    /**
     * 设置详细地址。
     *
     * @param detail 详细地址
     */
    public void setDetail(String detail) {
        this.detail = detail;
    }

    /**
     * 是否默认地址。
     *
     * @return 默认返回 true
     */
    public Boolean getIsDefault() {
        return isDefault;
    }

    /**
     * 设置默认标记。
     *
     * @param isDefault 默认标记
     */
    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }
}