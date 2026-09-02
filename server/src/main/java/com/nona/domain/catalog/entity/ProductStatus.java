package com.nona.domain.catalog.entity;

/**
 * 商品生命周期状态（Product 聚合状态字段，一期草稿形态）。
 * <p>
 * 一期仅落地草稿态：商品创建即 DRAFT，可保存不生效（无在售/展示路径）。
 * 状态机（draft → pending review → on sale ⇄ delisted，含提交审核与
 * 审核状态机）属后续阶段，届时本枚举扩展状态值——只增不改。
 *
 * @author nona9961
 */
public enum ProductStatus {

    /**
     * 草稿：商家编辑中，不对买家生效（可保存不生效）。
     */
    DRAFT
}