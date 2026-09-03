package com.nona.inf.persistence.po.catalog;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 商品可售单元持久化对象（product_sku 表，tenant=shopId）：Product 聚合
 * 的从表行（规格组合 + 独立价格 + 启用状态）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离。
 * product_id 为业务关联列（rootId 关联 product 主表）；spec_hash 为
 * 规范化摘要（SHA-256 hex 定长 64，与维度配置顺序无关），同商品内唯一
 * （uk_product_sku_spec_hash 唯一约束 + 聚合守卫双保险——并发重复落库
 * 由 DB 兜底拒绝）；spec_summary 为可读摘要（展示用，不参与唯一性）；
 * price 可空（未定价合法形态，0/负数由聚合路径拒绝）；enabled 默认停用
 * （fail-closed：商家显式启用）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "product_sku", uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_sku_spec_hash",
                columnNames = {"product_id", "spec_hash"})
}, indexes = {
        @Index(name = "idx_product_sku_product", columnList = "product_id")
})
public class SkuPO extends TenantScopedBasePO {

    /**
     * 归属商品 ID（rootId 关联）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * 规格组合规范化摘要（SHA-256 hex，64 字符；同商品内唯一）
     */
    @Column(nullable = false, length = 64, name = "spec_hash")
    private String specHash;

    /**
     * 规格组合可读摘要（配置序拼接，展示用）
     */
    @Column(nullable = false, length = 512, name = "spec_summary")
    private String specSummary;

    /**
     * 销售价（分；null=未定价）
     */
    @Column(name = "price")
    private Long price;

    /**
     * 是否启用（新生成默认停用）
     */
    @Column(nullable = false)
    private boolean enabled;

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
     * 规格组合规范化摘要。
     *
     * @return 摘要
     */
    public String getSpecHash() {
        return specHash;
    }

    /**
     * 设置规格组合规范化摘要。
     *
     * @param specHash 摘要
     */
    public void setSpecHash(String specHash) {
        this.specHash = specHash;
    }

    /**
     * 规格组合可读摘要。
     *
     * @return 摘要
     */
    public String getSpecSummary() {
        return specSummary;
    }

    /**
     * 设置规格组合可读摘要。
     *
     * @param specSummary 摘要
     */
    public void setSpecSummary(String specSummary) {
        this.specSummary = specSummary;
    }

    /**
     * 销售价（分）。
     *
     * @return 价格；未定价返回 null
     */
    public Long getPrice() {
        return price;
    }

    /**
     * 设置销售价（分）。
     *
     * @param price 价格；null=未定价
     */
    public void setPrice(Long price) {
        this.price = price;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置启用状态。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}