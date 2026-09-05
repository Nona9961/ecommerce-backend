package com.nona.inf.persistence.converters;

import com.nona.domain.order.entity.Cart;
import com.nona.inf.persistence.po.order.CartPO;
import com.nona.inf.persistence.po.order.CartItemPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 购物车聚合根 ↔ 购物车 PO 转换器（主表 cart + 从表 cart_item 行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 购物车主键，buyerId 为业务关联列）；从表行经
 * {@link CartItemConvertor} 逐行转换后由装载构造器恢复聚合（装载路径不
 * 校验可售上限——购物车为软状态，已持久化条目数量可能超过当前可售量）。
 * other 参数为从表行集合（读路径由仓储 getOther 提供）。
 *
 * @author nona9961
 */
@Component
public class CartConvertor extends AbstractConvertor<Cart, CartPO, List<CartItemPO>> {

    /**
     * 条目行转换器
     */
    private final CartItemConvertor cartItemConvertor;

    /**
     * 构造购物车转换器。
     *
     * @param cartItemConvertor 条目行转换器
     */
    public CartConvertor(CartItemConvertor cartItemConvertor) {
        this.cartItemConvertor = cartItemConvertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 主表行承载聚合根身份（id = 购物车主键）与业务关联（buyerId），
     * 审计时间戳由 JPA auditing 填充。
     */
    @Override
    protected CartPO safedConvertToPO(Cart root) {
        final CartPO po = new CartPO();
        po.setId(root.getId());
        po.setBuyerId(root.getBuyerId());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从表行逐个经 {@link CartItemConvertor} 转换并经装载构造器恢复聚合
     * （顺序保持加载序）。
     */
    @Override
    protected Cart safedConvertToRoot(CartPO po, List<CartItemPO> itemPOs) {
        return new Cart(po.getId(), po.getBuyerId(),
                itemPOs.stream().map(cartItemConvertor::toDomain).toList());
    }
}