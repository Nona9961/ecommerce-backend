package com.nona.domain.identity.entity;

/**
 * 收藏目标类型（买家收藏聚合：商品 / 店铺）。
 * <p>
 * 领域枚举与契约层枚举（api.mall.FavoriteType）同名同值，二者经用例层
 * 显式映射（契约演进时映射点唯一，参照门户与账号类型的映射先例）。
 *
 * @author nona9961
 */
public enum FavoriteType {

    /**
     * 商品（引用商品 ID；商品实体属目录域，跨域协作仅存引用）
     */
    PRODUCT,

    /**
     * 店铺（引用店铺 ID；店铺实体属目录域，跨域协作仅存引用）
     */
    SHOP
}