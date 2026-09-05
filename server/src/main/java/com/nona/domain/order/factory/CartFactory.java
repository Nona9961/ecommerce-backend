package com.nona.domain.order.factory;

import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 购物车聚合根工厂：购物车与条目的创建入口（ID 生成 + 字段形态校验）。
 * <p>
 * 聚合创建统一经工厂 + {@link IDUtils#generateID()}；购物车拥有独立主键
 * cartId，buyerId 仅作业务关联列；条目以 cartId（rootId）关联所属购物车，
 * 并以 buyerId + skuId 组成 (buyer,sku) 唯一键（同 SKU 单条目）。数量
 * ≤ 可售上限的校验在聚合方法（add/updateQuantity 以上限参数断言收敛，
 * 上限由用例层跨上下文读入），工厂只校验字段形态。
 *
 * @author nona9961
 */
@Component
public class CartFactory {

    /**
     * 创建空购物车（买家维度；账号存在性由登录链路保证）。
     * 主键 cartId 独立生成（Snowflake），buyerId 为业务关联列（一个买家
     * 一个购物车）。
     *
     * @param buyerId 归属买家账号 ID
     * @return 空购物车
     */
    public Cart createCart(Long buyerId) {
        BusinessAssert.assertNonNull(buyerId, "买家账号 ID 不能为空");
        return new Cart(IDUtils.generateID(), buyerId);
    }

    /**
     * 创建购物车条目（ID 由雪花算法生成；cartId/buyerId 取自所属购物车）。
     *
     * @param cart       所属购物车（cartId/buyerId 取自购物车）
     * @param productId  商品 ID（改量校验的读锚点）
     * @param skuId      SKU ID
     * @param quantity   数量（正数；上限校验在聚合方法）
     * @param checked    勾选标记（新条目默认勾选由调用方传入）
     * @param shopId     归属店铺 ID（分组锚点）
     * @param shopName   店铺名称快照（分组展示用，可空）
     * @return 新条目
     */
    public CartItem createItem(Cart cart, Long productId, Long skuId, int quantity,
                               boolean checked, Long shopId, String shopName) {
        BusinessAssert.assertNonNull(cart, "购物车不能为空");
        BusinessAssert.assertNonNull(productId, "商品 ID 不能为空");
        BusinessAssert.assertNonNull(skuId, "SKU ID 不能为空");
        BusinessAssert.assertTrue(quantity >= 1, "加购数量必须为正数：{}", quantity);
        BusinessAssert.assertNonNull(shopId, "店铺 ID 不能为空");
        return new CartItem(IDUtils.generateID(), cart.getId(), cart.getBuyerId(),
                productId, skuId, quantity, checked, shopId, shopName);
    }
}