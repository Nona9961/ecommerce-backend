package com.nona.domain.catalog.entity;

/**
 * 商品-店铺分类绑定行（Product 聚合从表 product_shop_category_rel 行的
 * 域内承载，值对象——不可变，无独立行为）。
 * <p>
 * 多对多语义：商品（Product 聚合）可属多个店铺分类（店铺分类
 * 实体生命周期归 Shop 聚合，商品侧只持分类引用 ID）；从表行以
 * productId（rootId）归属商品主表，行主键为独立 Snowflake ID（行自身
 * 无业务身份，主键供变更追踪按元素身份驱动落库）；(product_id,
 * shop_category_id) 唯一约束 + 聚合内去重双保险。分类删除守卫（删除
 * 前须零商品引用）在用例层经引用查询承载。
 *
 * @param id             绑定行 ID（Snowflake 从表行主键）
 * @param productId      归属商品 ID（rootId 关联）
 * @param shopCategoryId 店铺分类 ID（Shop 聚合内实体引用；必填非空）
 * @author nona9961
 */
public record ProductShopCategoryRef(Long id, Long productId, Long shopCategoryId) {

    /**
     * 紧凑构造器：防御形态非法（身份三要素缺失的绑定行无落库意义——
     * 行主键/归属/引用目标均必填）。
     *
     * @param id             绑定行 ID
     * @param productId      归属商品 ID
     * @param shopCategoryId 店铺分类 ID
     */
    public ProductShopCategoryRef {
        if (id == null) {
            throw new IllegalArgumentException("绑定行ID不能为空");
        }
        if (productId == null) {
            throw new IllegalArgumentException("归属商品不能为空");
        }
        if (shopCategoryId == null) {
            throw new IllegalArgumentException("店铺分类不能为空");
        }
    }
}
