package com.nona.domain.catalog.entity;

/**
 * 店铺状态（Shop 聚合根状态值）：一期仅承载状态位，
 * 冻结状态与商品可售性的联动拦截属后续阶段（上架/在售迁移在用例层按此状态守卫）。
 *
 * @author nona9961
 */
public enum ShopStatus {

    /**
     * 正常：店铺可运营、商品可上架在售。
     */
    NORMAL,

    /**
     * 冻结：商品不可售（封禁联动后续阶段实现，一期仅建状态位）。
     */
    FROZEN
}