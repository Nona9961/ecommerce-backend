package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 店铺分类请求体（商家端）：新增 / 改名共用。
 *
 * @param name 分类名称（必填）
 */
public record ShopCategoryRequest(
        @NotBlank(message = "分类名称不能为空") String name
) {
}