package com.nona.api.admin;

/**
 * 平台分类条目（平台端列表与操作返回形态）。
 *
 * @param id     分类 ID
 * @param name   分类名称
 * @param order  展示排序（正数；列表按此升序）
 * @param status 分类状态（ENABLED 启用 / DISABLED 禁用）
 * @author nona9961
 */
public record PlatformCategoryItem(
        Long id,
        String name,
        Integer order,
        String status
) {
}