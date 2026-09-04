package com.nona.inf.persistence.po.catalog;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 商品-店铺分类绑定持久化对象（product_shop_category_rel 从表，
 * tenant=shopId）：商品（Product 聚合根）与店铺分类的多对多绑定行。
 * <p>
 * 从表行以 product_id（rootId）归属商品主表，行主键为独立 Snowflake
 * ID（行自身无业务身份，主键供变更追踪按元素身份驱动落库）；
 * (product_id, shop_category_id) 唯一约束 + 索引（重复绑定无业务意义，
 * 聚合内去重双保险）。多对多语义：商品侧只持分类引用 ID，店铺分类实体
 * 生命周期归 Shop 聚合（本表不承载分类内容）；分类删除守卫（删除前须
 * 零商品引用）在用例层经引用查询承载。
 *
 * @author nona9961
 */
@Entity
@Table(name = "product_shop_category_rel", uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_shop_category_rel",
                columnNames = {"product_id", "shop_category_id"})
}, indexes = {
        @Index(name = "idx_product_shop_category_rel_product", columnList = "product_id")
})
public class ProductShopCategoryRelPO extends TenantScopedBasePO {

    /**
     * 归属商品 ID（rootId 关联）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * 店铺分类 ID（Shop 聚合内实体引用）
     */
    @Column(nullable = false, name = "shop_category_id")
    private Long shopCategoryId;

    /**
     * 归属商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * 设置归属商品 ID。
     *
     * @param productId 商品 ID
     */
    public void setProductId(Long productId) {
        this.productId = productId;
    }

    /**
     * 店铺分类 ID。
     *
     * @return 分类 ID
     */
    public Long getShopCategoryId() {
        return shopCategoryId;
    }

    /**
     * 设置店铺分类 ID。
     *
     * @param shopCategoryId 分类 ID
     */
    public void setShopCategoryId(Long shopCategoryId) {
        this.shopCategoryId = shopCategoryId;
    }
}