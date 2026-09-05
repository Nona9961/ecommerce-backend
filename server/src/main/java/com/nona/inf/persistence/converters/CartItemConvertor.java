package com.nona.inf.persistence.converters;

import com.nona.domain.order.entity.CartItem;
import com.nona.inf.persistence.po.order.CartItemPO;
import org.springframework.stereotype.Component;

/**
 * 购物车条目实体 ↔ 条目 PO 转换器（cart_item 表单行映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：CartItem 是购物车聚合内的子
 * 实体，行级读写不需要聚合上下文参数。
 *
 * @author nona9961
 */
@Component
public class CartItemConvertor implements PoConverter<CartItem, CartItemPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<CartItem> domainClass() {
        return CartItem.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<CartItemPO> poClass() {
        return CartItemPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，
     * 转换不负责。
     */
    @Override
    public CartItemPO toPO(CartItem domain) {
        final CartItemPO po = new CartItemPO();
        po.setId(domain.getId());
        po.setCartId(domain.getCartId());
        po.setBuyerId(domain.getBuyerId());
        po.setProductId(domain.getProductId());
        po.setSkuId(domain.getSkuId());
        po.setQuantity(domain.getQuantity());
        po.setChecked(domain.isChecked());
        po.setShopId(domain.getShopId());
        po.setShopName(domain.getShopName());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CartItem toDomain(CartItemPO po) {
        return new CartItem(po.getId(), po.getCartId(), po.getBuyerId(), po.getProductId(),
                po.getSkuId(), po.getQuantity(), Boolean.TRUE.equals(po.getChecked()),
                po.getShopId(), po.getShopName());
    }
}