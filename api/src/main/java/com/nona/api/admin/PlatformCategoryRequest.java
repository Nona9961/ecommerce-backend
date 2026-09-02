package com.nona.api.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * 平台分类请求体（平台端）：创建 / 更新共用。
 *
 * @param name  分类名称（必填；全局唯一，含禁用态不可复用）
 * @param order 展示排序（可空；创建时空缺自动取当前最大排序 + 1，
 *              更新时空缺保持原排序；非正数视为空缺，不维护负数排序）
 */
public record PlatformCategoryRequest(
        @NotBlank(message = "分类名称不能为空") String name,
        Integer order
) {
}