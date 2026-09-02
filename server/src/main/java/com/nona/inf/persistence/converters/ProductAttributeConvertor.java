package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.inf.persistence.po.catalog.ProductAttributePO;
import org.springframework.stereotype.Component;

/**
 * 商品自定义属性实体 ↔ PO 转换器（product_attribute 表单行映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：ProductAttribute 是商品聚合内的
 * 子实体，行级读写不需要聚合上下文参数。租户列（tenant_id）由写门禁按
 * 请求上下文注入（归属=当前店铺），转换不负责设置。
 *
 * @author nona9961
 */
@Component
public class ProductAttributeConvertor implements PoConverter<ProductAttribute, ProductAttributePO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductAttribute> domainClass() {
        return ProductAttribute.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductAttributePO> poClass() {
        return ProductAttributePO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳与租户列由持久化层填充，转换不负责。
     */
    @Override
    public ProductAttributePO toPO(ProductAttribute domain) {
        final ProductAttributePO po = new ProductAttributePO();
        po.setId(domain.getId());
        po.setProductId(domain.getProductId());
        po.setAttrKey(domain.getKey());
        po.setAttrValue(domain.getValue());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductAttribute toDomain(ProductAttributePO po) {
        return new ProductAttribute(po.getId(), po.getProductId(), po.getAttrKey(), po.getAttrValue());
    }
}