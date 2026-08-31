package com.nona.api.mall;

import jakarta.validation.constraints.NotBlank;

/**
 * 收货地址请求体：新增与编辑共用的契约。
 * <p>
 * 省市区拆分为三个字段（province/city/district），与详细地址（detail）分离——
 * 下单快照与物流面单均按此结构使用；电话仅做必填校验，不限定号段
 * （移动/固话/国际号码形态各异，超范围约束会误伤合法输入）。
 *
 * @param recipient 收件人（必填）
 * @param phone     联系电话（必填）
 * @param province  省份（必填）
 * @param city      城市（必填）
 * @param district  区县（必填）
 * @param detail    详细地址（必填）
 * @param isDefault 是否设为默认地址；编辑路径忽略该字段（默认标记仅由新增与「设置默认」变更）
 */
public record AddressRequest(
        @NotBlank(message = "收件人不能为空") String recipient,
        @NotBlank(message = "联系电话不能为空") String phone,
        @NotBlank(message = "省份不能为空") String province,
        @NotBlank(message = "城市不能为空") String city,
        @NotBlank(message = "区县不能为空") String district,
        @NotBlank(message = "详细地址不能为空") String detail,
        Boolean isDefault
) {
}