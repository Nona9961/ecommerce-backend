package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 入驻申请资料（提交 / 编辑 / 重提共用请求体）。
 * <p>
 * 字段形态校验（非空 + 长度上限）由 JSR-380 在此兜底；领域层仍有
 * 防御性断言，防止绕过 API 的直接调用路径。
 *
 * @param shopName     店铺名
 * @param contactName  联系人
 * @param contactPhone 联系方式
 */
public record OnboardingApplicationRequest(
        @NotBlank @Size(max = 128) String shopName,
        @NotBlank @Size(max = 64) String contactName,
        @NotBlank @Size(max = 32) String contactPhone
) {
}