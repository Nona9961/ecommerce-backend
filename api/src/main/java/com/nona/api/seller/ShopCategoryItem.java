package com.nona.api.seller;

/**
 * 店铺分类条目响应体（店铺详情内嵌与增删改响应共用）。
 *
 * @param id    分类 ID
 * @param name  分类名称
 * @param order 展示排序（新增自动分配，删除不重排）
 */
public record ShopCategoryItem(
        Long id,
        String name,
        int order
) {
}