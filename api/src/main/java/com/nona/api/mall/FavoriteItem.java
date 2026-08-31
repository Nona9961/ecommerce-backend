package com.nona.api.mall;

/**
 * 收藏条目响应体（收藏列表页记录，按收藏时间倒序）。
 *
 * @param id         收藏条目 ID
 * @param targetType 收藏目标类型（PRODUCT 商品 / SHOP 店铺）
 * @param targetId   收藏目标 ID
 * @param createTime 收藏时间（ISO 8601 字符串）
 */
public record FavoriteItem(
        Long id,
        FavoriteType targetType,
        Long targetId,
        String createTime
) {
}