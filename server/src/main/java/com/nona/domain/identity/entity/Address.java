package com.nona.domain.identity.entity;

/**
 * 收货地址（AddressBook 聚合内实体）：地址簿的组成单元，一行对应 address 表一条记录。
 * <p>
 * 携带归属键 accountId（与所属地址簿一致，创建时定型不可变）——持久化行级映射所需，
 * 归属一致性由工厂与聚合保证（地址只经 AddressBook 聚合装载与操作，不单独游离存在）。
 * 默认标记（isDefault）不参与编辑（编辑只更新地址字段），默认标记的变更路径只有
 * 「新增时标记」与「设置默认」两种，保证默认唯一不变量集中在聚合内闭环。
 *
 * @author nona9961
 */
public class Address {

    /**
     * 地址 ID（Snowflake）
     */
    private final Long id;

    /**
     * 归属买家账号 ID（与所属地址簿一致）
     */
    private final Long accountId;

    /**
     * 收件人
     */
    private String recipient;

    /**
     * 联系电话
     */
    private String phone;

    /**
     * 省份
     */
    private String province;

    /**
     * 城市
     */
    private String city;

    /**
     * 区县
     */
    private String district;

    /**
     * 详细地址
     */
    private String detail;

    /**
     * 是否默认地址（同买家至多一个）
     */
    private boolean isDefault;

    /**
     * 构造地址（仅 Factory 与聚合加载重建调用）。
     *
     * @param id        地址 ID
     * @param accountId 归属买家账号 ID
     * @param recipient 收件人
     * @param phone     联系电话
     * @param province  省份
     * @param city      城市
     * @param district  区县
     * @param detail    详细地址
     * @param isDefault 是否默认地址
     */
    public Address(Long id, Long accountId, String recipient, String phone,
                   String province, String city, String district, String detail, boolean isDefault) {
        this.id = id;
        this.accountId = accountId;
        this.recipient = recipient;
        this.phone = phone;
        this.province = province;
        this.city = city;
        this.district = district;
        this.detail = detail;
        this.isDefault = isDefault;
    }

    /**
     * 地址 ID。
     *
     * @return 地址 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getAccountId() {
        return accountId;
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
     * 联系电话。
     *
     * @return 联系电话
     */
    public String getPhone() {
        return phone;
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
     * 城市。
     *
     * @return 城市
     */
    public String getCity() {
        return city;
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
     * 详细地址。
     *
     * @return 详细地址
     */
    public String getDetail() {
        return detail;
    }

    /**
     * 是否默认地址。
     *
     * @return 默认返回 true
     */
    public boolean isDefault() {
        return isDefault;
    }

    /**
     * 更新地址字段（编辑路径唯一入口；不触碰默认标记）。
     *
     * @param recipient 收件人
     * @param phone     联系电话
     * @param province  省份
     * @param city      城市
     * @param district  区县
     * @param detail    详细地址
     */
    public void updateDetails(String recipient, String phone, String province,
                              String city, String district, String detail) {
        this.recipient = recipient;
        this.phone = phone;
        this.province = province;
        this.city = city;
        this.district = district;
        this.detail = detail;
    }

    /**
     * 设置默认标记（仅供 {@link AddressBook} 统一迁移调用，外部不得直接变更）。
     *
     * @param isDefault 默认标记
     */
    void markDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}