package com.nona.api.admin;

import com.nona.api.common.ProductLifecycleStatus;

/**
 * 商品审核条目（平台待审/全量列表项）：商品概要与生命周期状态，管理员
 * 视角跨店铺全集（审核结论沿商品状态机迁移落位，无独立审核单）。
 * <p>
 * 派生字段（图片数/启用 SKU 数）供审核人快速判断资料完整度；店铺 ID
 * 冗余承载归属（跨店铺审核定位）。状态过滤与分页在查询侧完成。
 *
 * @param id              商品 ID
 * @param shopId          归属店铺 ID
 * @param name            商品名称
 * @param categoryId      平台类目 ID（可空引用）
 * @param brandId         品牌 ID（可空引用）
 * @param imageCount      图片引用数
 * @param enabledSkuCount 启用 SKU 数
 * @param status          商品生命周期状态
 */
public record ProductReviewItem(
        Long id,
        Long shopId,
        String name,
        Long categoryId,
        Long brandId,
        int imageCount,
        int enabledSkuCount,
        ProductLifecycleStatus status
) {
}