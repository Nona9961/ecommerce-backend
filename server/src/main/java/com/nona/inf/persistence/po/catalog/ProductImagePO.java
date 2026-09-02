package com.nona.inf.persistence.po.catalog;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 商品图片引用持久化对象（product_image 表，tenant=shopId）：Product 聚合
 * 的从表行（画廊：URL 引用 + 主图标记）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离。
 * product_id 为业务关联列（rootId 关联 product 主表）；url 只存
 * {@code /files/{objectKey}} 形态字符串（上传由文件上传端点产出）；
 * is_primary 标记主图（同一商品至多一条为 true，聚合内唯一性保证）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "product_image", indexes = {
        @Index(name = "idx_product_image_product", columnList = "product_id")
})
public class ProductImagePO extends TenantScopedBasePO {

    /**
     * 归属商品 ID（rootId 关联）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * 图片 URL（/files/{objectKey} 形态）
     */
    @Column(nullable = false, length = 512)
    private String url;

    /**
     * 是否主图（同一商品至多一条为 true）
     */
    @Column(nullable = false, name = "is_primary")
    private boolean primary;

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
     * 图片 URL。
     *
     * @return URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 设置图片 URL。
     *
     * @param url URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * 是否主图。
     *
     * @return 主图返回 true
     */
    public boolean isPrimary() {
        return primary;
    }

    /**
     * 设置主图标记。
     *
     * @param primary 是否主图
     */
    public void setPrimary(boolean primary) {
        this.primary = primary;
    }
}