package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.ProductShopCategoryRef;
import com.nona.inf.persistence.po.catalog.ProductShopCategoryRelPO;
import org.springframework.stereotype.Component;

/**
 * 商品-店铺分类绑定行 ↔ 持久化对象转换器（product_shop_category_rel
 * 从表行与域内绑定值对象一一对应）。
 *
 * @author nona9961
 */
@Component
public class ProductShopCategoryRelConvertor implements PoConverter<ProductShopCategoryRef, ProductShopCategoryRelPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductShopCategoryRef> domainClass() {
        return ProductShopCategoryRef.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductShopCategoryRelPO> poClass() {
        return ProductShopCategoryRelPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 行主键 = 绑定行 ID（Snowflake）；归属商品与分类引用原样承载。
     */
    @Override
    public ProductShopCategoryRelPO toPO(ProductShopCategoryRef domain) {
        final ProductShopCategoryRelPO po = new ProductShopCategoryRelPO();
        po.setId(domain.id());
        po.setProductId(domain.productId());
        po.setShopCategoryId(domain.shopCategoryId());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductShopCategoryRef toDomain(ProductShopCategoryRelPO po) {
        return new ProductShopCategoryRef(po.getId(), po.getProductId(), po.getShopCategoryId());
    }
}