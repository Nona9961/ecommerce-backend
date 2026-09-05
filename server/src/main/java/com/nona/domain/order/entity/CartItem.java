package com.nona.domain.order.entity;

/**
 * 购物车条目（Cart 聚合内实体，对应 cart_item 表行）：同一买家同一
 * SKU 在购物车至多一条（数量累加语义）。
 * <p>
 * 归属键：cartId（所属购物车主键，rootId 关联）+ buyerId（业务关联列，
 * (buyer,sku) 唯一约束由持久化层承载）；productId 冗余（改量校验从买家
 * 商品视图读上限的锚点）；shopId 为店铺分组锚点，shopName 为创建时快照
 * （展示软状态，商家改名后展示旧名直至下次加购——订单落单时的店铺信息
 * 冻结属订单快照语义）。
 * <p>
 * 数量与勾选标记的变更入口在聚合方法（{@link Cart} add/updateQuantity/
 * toggleChecked/checkAll 统一迁移），本实体只暴露包级字段变更方法——
 * 数量非负与上限校验由聚合收敛，外部不得绕过。
 *
 * @author nona9961
 */
public class CartItem {

    /**
     * 条目 ID（Snowflake）
     */
    private final Long id;

    /**
     * 所属购物车主键（rootId 关联，创建时定型不可变）
     */
    private final Long cartId;

    /**
     * 归属买家账号 ID（业务关联列，(buyer,sku) 唯一约束组成）
     */
    private final Long buyerId;

    /**
     * 商品 ID（冗余：改量校验按商品读在售与可售上限的锚点）
     */
    private final Long productId;

    /**
     * SKU ID（库存/价格维度的可售单元；购物车内定位键）
     */
    private final Long skuId;

    /**
     * 数量（正数且 ≤ 可售上限）
     */
    private int quantity;

    /**
     * 结算勾选标记（仅勾选项进入结算）
     */
    private boolean checked;

    /**
     * 归属店铺 ID（按店铺分组展示的锚点）
     */
    private final Long shopId;

    /**
     * 店铺名称（创建时快照，分组展示用）
     */
    private final String shopName;

    /**
     * 构造购物车条目（仅 Factory 与聚合装载调用）。
     *
     * @param id        条目 ID
     * @param cartId    所属购物车主键
     * @param buyerId   归属买家账号 ID
     * @param productId 商品 ID
     * @param skuId     SKU ID
     * @param quantity  数量
     * @param checked   勾选标记
     * @param shopId    归属店铺 ID
     * @param shopName  店铺名称快照（可空）
     */
    public CartItem(Long id, Long cartId, Long buyerId, Long productId, Long skuId,
                    int quantity, boolean checked, Long shopId, String shopName) {
        this.id = id;
        this.cartId = cartId;
        this.buyerId = buyerId;
        this.productId = productId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.checked = checked;
        this.shopId = shopId;
        this.shopName = shopName;
    }

    /**
     * 条目 ID。
     *
     * @return 条目 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 所属购物车主键。
     *
     * @return 购物车主键
     */
    public Long getCartId() {
        return cartId;
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
     * 商品 ID。
     *
     * @return 商品 ID
     */
    public Long getProductId() {
        return productId;
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
     * 数量。
     *
     * @return 数量
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * 是否勾选结算。
     *
     * @return 勾选返回 true
     */
    public boolean isChecked() {
        return checked;
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
     * 店铺名称快照。
     *
     * @return 店铺名称；创建时缺失返回 null
     */
    public String getShopName() {
        return shopName;
    }

    /**
     * 数量累加（同 SKU 重复加购路径；仅供 {@link Cart} 统一迁移调用）。
     *
     * @param delta 累加量（正数）
     */
    void mergeQuantity(int delta) {
        this.quantity += delta;
    }

    /**
     * 数量替换（改量路径，绝对量；仅供 {@link Cart} 统一迁移调用）。
     *
     * @param quantity 新数量
     */
    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /**
     * 设置勾选标记（仅供 {@link Cart} toggleChecked/checkAll 统一迁移调用）。
     *
     * @param checked 勾选状态
     */
    void setChecked(boolean checked) {
        this.checked = checked;
    }
}