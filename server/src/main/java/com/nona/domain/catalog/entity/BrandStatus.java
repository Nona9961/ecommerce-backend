package com.nona.domain.catalog.entity;

/**
 * 品牌状态（Brand 聚合根状态值）：禁用 = 软删（disable-not-delete）。
 * <p>
 * 禁用语义：既有商品保持可见，新商品不可挂品牌——
 * 挂载侧校验在商品域（Product 挂品牌时校验目标 ENABLED），
 * 本枚举承载语义字段就位。
 *
 * @author nona9961
 */
public enum BrandStatus {

    /**
     * 启用：可作为新商品的挂载目标。
     */
    ENABLED,

    /**
     * 禁用：既有商品保持可见，新挂载拒绝。
     */
    DISABLED
}