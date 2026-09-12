package com.nona.api.seller;

/**
 * 收货地址快照响应体（商家订单详情内嵌，对齐 AddressSnapshot 实体——
 * 创建时固化不可变）。
 *
 * @param recipient 收货人姓名
 * @param phone     联系电话
 * @param province  省
 * @param city      市
 * @param district  区/县
 * @param detail    详细地址
 * @author nona9961
 */
public record SellerOrderAddress(
        String recipient,
        String phone,
        String province,
        String city,
        String district,
        String detail
) {
}