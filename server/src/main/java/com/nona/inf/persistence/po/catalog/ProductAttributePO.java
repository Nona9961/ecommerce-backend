package com.nona.inf.persistence.po.catalog;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 商品自定义属性持久化对象（product_attribute 表，tenant=shopId）：Product
 * 聚合的从表行（属性键值对）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离。
 * product_id 为业务关联列（rootId 关联 product 主表）；attr_key / attr_value
 * 为键值列（键同商品内唯一由聚合保证，键必填；值可空）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "product_attribute", indexes = {
        @Index(name = "idx_product_attribute_product", columnList = "product_id")
})
public class ProductAttributePO extends TenantScopedBasePO {

    /**
     * 归属商品 ID（rootId 关联）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * 属性键（同商品内唯一，必填）
     */
    @Column(nullable = false, length = 64, name = "attr_key")
    private String attrKey;

    /**
     * 属性值（可空）
     */
    @Column(length = 512, name = "attr_value")
    private String attrValue;

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
     * 属性键。
     *
     * @return 属性键
     */
    public String getAttrKey() {
        return attrKey;
    }

    /**
     * 设置属性键。
     *
     * @param attrKey 属性键
     */
    public void setAttrKey(String attrKey) {
        this.attrKey = attrKey;
    }

    /**
     * 属性值。
     *
     * @return 属性值；未填写为 null
     */
    public String getAttrValue() {
        return attrValue;
    }

    /**
     * 设置属性值。
     *
     * @param attrValue 属性值
     */
    public void setAttrValue(String attrValue) {
        this.attrValue = attrValue;
    }
}