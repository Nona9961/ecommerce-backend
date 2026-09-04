package com.nona.api.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * 品牌请求体（平台端）：创建 / 更新共用。
 *
 * @param name 品牌名称（必填；全局唯一，含禁用态不可复用）
 * @param logo 品牌 logo URL（可空；更新传 null 表示清除）
 * @author nona9961
 */
public record BrandRequest(
        @NotBlank(message = "品牌名称不能为空") String name,
        String logo
) {
}