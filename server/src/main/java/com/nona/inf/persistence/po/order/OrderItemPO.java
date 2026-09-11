package com.nona.inf.persistence.po.order;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 子订单订单项快照持久化对象（order_item 从表，tenant=shopId——从表
 * 随主表租户化先例见 ProductImagePO）：子单的一行商品快照，归属
 * sub_order 主键（sub_order_id，rootId 关联）。
 * <p>
 * 列模型：高频字段为列——商品名/单价/数量/小计/主图 URL/
 * SKU 规格摘要文本；半结构化内容（规格键值对、自定义属性快照）入 JSON
 * 扩展列（spec_attributes/custom_attributes，longtext，@JdbcTypeCode
 * (LONGVARCHAR)——V1.2 定稿形态，Map 序列化由 OrderItemConvertor 以
 * Jackson 承担）。业务不变量以联合唯一约束落地：(sub_order_id, sku_id)
 * 唯一——同一子单同一 SKU 至多一个订单项。
 *
 * @author nona9961
 */
@Entity
@Table(name = "order_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_item_sub_sku",
                columnNames = {"sub_order_id", "sku_id"})
}, indexes = {
        @Index(name = "idx_order_item_sub_order", columnList = "sub_order_id")
})
public class OrderItemPO extends TenantScopedBasePO {

    /**
     * 所属子订单主键（rootId 关联）
     */
    @Column(nullable = false, name = "sub_order_id")
    private Long subOrderId;

    /**
     * 商品 ID（快照溯源/售后锚点）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * SKU ID（库存/退款维度锚点，(sub_order, sku) 唯一约束组成）
     */
    @Column(nullable = false, name = "sku_id")
    private Long skuId;

    /**
     * 商品名快照（防改价错乱）
     */
    @Column(nullable = false, length = 255, name = "product_name")
    private String productName;

    /**
     * 快照单价（分，非负）
     */
    @Column(nullable = false, name = "unit_price")
    private Long unitPrice;

    /**
     * 数量（正数）
     */
    @Column(nullable = false)
    private Integer quantity;

    /**
     * 小计（分，快照列：单价 × 数量）
     */
    @Column(nullable = false)
    private Long subtotal;

    /**
     * 主图 URL 快照（可空——下单时商品主图缺失的兜底形态）
     */
    @Column(length = 512, name = "main_image_url")
    private String mainImageUrl;

    /**
     * SKU 规格摘要文本（可空：无规格 SKU）
     */
    @Column(length = 255, name = "spec_summary")
    private String specSummary;

    /**
     * 规格键值对快照（JSON 扩展列，保序 Map 序列化；可空=无规格明细）
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "spec_attributes")
    private String specAttributes;

    /**
     * 自定义属性快照（JSON 扩展列，保序 Map 序列化；可空=无自定义属性）
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "custom_attributes")
    private String customAttributes;

    public Long getSubOrderId() {
        return subOrderId;
    }

    public void setSubOrderId(Long subOrderId) {
        this.subOrderId = subOrderId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(Long unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Long getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(Long subtotal) {
        this.subtotal = subtotal;
    }

    public String getMainImageUrl() {
        return mainImageUrl;
    }

    public void setMainImageUrl(String mainImageUrl) {
        this.mainImageUrl = mainImageUrl;
    }

    public String getSpecSummary() {
        return specSummary;
    }

    public void setSpecSummary(String specSummary) {
        this.specSummary = specSummary;
    }

    public String getSpecAttributes() {
        return specAttributes;
    }

    public void setSpecAttributes(String specAttributes) {
        this.specAttributes = specAttributes;
    }

    public String getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(String customAttributes) {
        this.customAttributes = customAttributes;
    }
}