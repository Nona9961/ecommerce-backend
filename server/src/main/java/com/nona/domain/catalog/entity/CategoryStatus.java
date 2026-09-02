package com.nona.domain.catalog.entity;

/**
 * 平台分类状态（PlatformCategory 聚合根状态值）：
 * 禁用 = 软删（disable-not-delete）。
 * <p>
 * 禁用语义：既有商品保持历史挂载可见，新商品不可挂载——
 * 挂载侧守卫属商品域（Product 挂分类时校验目标 ENABLED）后续工作，
 * 本枚举承载语义字段就位。
 *
 * @author nona9961
 */
public enum CategoryStatus {

    /**
     * 启用：可作为新商品的挂载目标。
     */
    ENABLED,

    /**
     * 禁用：既有挂载保持（历史归属不失效），新挂载拒绝。
     */
    DISABLED
}