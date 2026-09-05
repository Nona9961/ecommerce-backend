package com.nona.inf.persistence.po.order;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 购物车条目持久化对象（cart_item 表，global）：购物车的一行，
 * 归属购物车主键（cart_id，rootId 关联 cart 表）。
 * <p>
 * 业务不变量以联合唯一约束落地：(buyer_id, sku_id) 唯一——同一买家
 * 同一 SKU 至多一条目（同 SKU 单条目语义，重复加购数量累加）。buyer_id
 * 为业务关联列冗余（唯一约束组成，归属一致性经聚合保证）；product_id
 * 冗余（改量校验的读锚点）；shop_id/shop_name 为按店铺分组展示的
 * 分组锚点与名称快照（展示软状态）。买家数据全局可见（不随店铺租户隔离）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "cart_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_item_buyer_sku",
                columnNames = {"buyer_id", "sku_id"})
}, indexes = {
        @Index(name = "idx_cart_item_cart", columnList = "cart_id")
})
public class CartItemPO extends BasePO {

    /**
     * 所属购物车主键（rootId 关联）
     */
    @Column(nullable = false, name = "cart_id")
    private Long cartId;

    /**
     * 归属买家账号 ID（业务关联列，(buyer,sku) 唯一约束组成）
     */
    @Column(nullable = false, name = "buyer_id")
    private Long buyerId;

    /**
     * 商品 ID（改量校验的读锚点）
     */
    @Column(nullable = false, name = "product_id")
    private Long productId;

    /**
     * SKU ID（可售单元）
     */
    @Column(nullable = false, name = "sku_id")
    private Long skuId;

    /**
     * 数量（正数；上限校验在应用层经买家商品视图读入）
     */
    @Column(nullable = false)
    private Integer quantity;

    /**
     * 结算勾选标记
     */
    @Column(nullable = false)
    private Boolean checked;

    /**
     * 归属店铺 ID（按店铺分组展示的锚点）
     */
    @Column(nullable = false, name = "shop_id")
    private Long shopId;

    /**
     * 店铺名称（创建时快照，分组展示用）
     */
    @Column(nullable = false, name = "shop_name")
    private String shopName;

    /**
     * 所属购物车主键。
     *
     * @return 购物车主键
     */
    public Long getCartId() {
        return cartId;
    }

    /**
     * 设置所属购物车主键。
     *
     * @param cartId 购物车主键
     */
    public void setCartId(Long cartId) {
        this.cartId = cartId;
    }

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getBuyerId() {
        return buyerId;
    }

    /**
     * 设置归属买家账号 ID。
     *
     * @param buyerId 买家账号 ID
     */
    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
    }

    /**
     * 商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * 设置商品 ID。
     *
     * @param productId 商品 ID
     */
    public void setProductId(Long productId) {
        this.productId = productId;
    }

    /**
     * SKU ID。
     *
     * @return SKU ID
     */
    public Long getSkuId() {
        return skuId;
    }

    /**
     * 设置 SKU ID。
     *
     * @param skuId SKU ID
     */
    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    /**
     * 数量。
     *
     * @return 数量
     */
    public Integer getQuantity() {
        return quantity;
    }

    /**
     * 设置数量。
     *
     * @param quantity 数量
     */
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    /**
     * 结算勾选标记。
     *
     * @return 勾选返回 true
     */
    public Boolean getChecked() {
        return checked;
    }

    /**
     * 设置勾选标记。
     *
     * @param checked 勾选标记
     */
    public void setChecked(Boolean checked) {
        this.checked = checked;
    }

    /**
     * 归属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 设置归属店铺 ID。
     *
     * @param shopId 店铺 ID
     */
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    /**
     * 店铺名称快照。
     *
     * @return 店铺名称
     */
    public String getShopName() {
        return shopName;
    }

    /**
     * 设置店铺名称快照。
     *
     * @param shopName 店铺名称
     */
    public void setShopName(String shopName) {
        this.shopName = shopName;
    }
}