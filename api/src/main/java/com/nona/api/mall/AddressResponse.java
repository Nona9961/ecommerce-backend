package com.nona.api.mall;

/**
 * 收货地址响应体：地址簿列表与增删改查的返回形态。
 *
 * @param id        地址 ID
 * @param recipient 收件人
 * @param phone     联系电话
 * @param province  省份
 * @param city      城市
 * @param district  区县
 * @param detail    详细地址
 * @param isDefault 是否为默认地址（同买家至多一个为 true）
 */
public record AddressResponse(
        Long id,
        String recipient,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        boolean isDefault
) {
}