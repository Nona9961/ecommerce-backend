package com.nona.domain.order.entity;

import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

/**
 * 收货地址快照（order 域值对象，不可变）：下单时刻收货信息的完整固化
 * （TD-09 地址快照列模型——收件人/电话/省市区/详细地址为独立列）。
 * <p>
 * 快照冻结语义：创建后不可变（B7.6 地址固化）——下单后地址簿变更不
 * 影响已生成订单；字段全 final、无任何变更路径。
 * <p>
 * 结构不变量（收敛在构造路径）：六段全部必填非空——订单地址必须完整
 * 可交付，任一缺失即拒（与身份域地址簿工厂校验语义对齐）。
 *
 * @author nona9961
 */
public class AddressSnapshot {

    /**
     * 收件人
     */
    private final String recipient;

    /**
     * 联系电话
     */
    private final String phone;

    /**
     * 省份
     */
    private final String province;

    /**
     * 城市
     */
    private final String city;

    /**
     * 区县
     */
    private final String district;

    /**
     * 详细地址
     */
    private final String detail;

    /**
     * 构造地址快照（六段必填校验收敛在本构造路径）。
     *
     * @param recipient 收件人（必填非空）
     * @param phone     联系电话（必填非空）
     * @param province  省份（必填非空）
     * @param city      城市（必填非空）
     * @param district  区县（必填非空）
     * @param detail    详细地址（必填非空）
     */
    public AddressSnapshot(String recipient, String phone, String province,
                           String city, String district, String detail) {
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code(),
                isNotBlank(recipient), "地址快照收件人不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code(),
                isNotBlank(phone), "地址快照联系电话不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code(),
                isNotBlank(province), "地址快照省份不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code(),
                isNotBlank(city), "地址快照城市不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code(),
                isNotBlank(district), "地址快照区县不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SNAPSHOT_ADDRESS_INVALID.code(),
                isNotBlank(detail), "地址快照详细地址不能为空");
        this.recipient = recipient;
        this.phone = phone;
        this.province = province;
        this.city = city;
        this.district = district;
        this.detail = detail;
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
     * 空白判定（null 或全空白）。
     *
     * @param value 待判定文本
     * @return 非 null 且含非空白字符返回 true
     */
    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}