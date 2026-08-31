package com.nona.api.mall;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 收藏 / 取消收藏请求体（幂等语义：重复收藏不报错不重复；取消不存在的收藏不报错）。
 *
 * @param targetType 收藏目标类型（PRODUCT 商品 / SHOP 店铺）
 * @param targetId   收藏目标 ID（引用 ID，不建外键；目标实体属目录域）
 */
public record FavoriteRequest(
        @NotNull FavoriteType targetType,
        @NotNull @Positive Long targetId
) {
}