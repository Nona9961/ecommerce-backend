package com.nona.api.admin;

/**
 * 品牌条目（平台端列表与操作返回形态）。
 *
 * @param id     品牌 ID
 * @param name   品牌名称
 * @param logo   品牌 logo URL（可空）
 * @param status 品牌状态（ENABLED 启用 / DISABLED 禁用）
 */
public record BrandItem(
        Long id,
        String name,
        String logo,
        String status
) {
}