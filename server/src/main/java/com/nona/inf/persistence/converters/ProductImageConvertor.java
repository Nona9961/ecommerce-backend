package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.ProductImage;
import com.nona.inf.persistence.po.catalog.ProductImagePO;
import org.springframework.stereotype.Component;

/**
 * 商品图片引用实体 ↔ PO 转换器（product_image 表单行映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：ProductImage 是商品聚合内的子实体，
 * 行级读写不需要聚合上下文参数。租户列（tenant_id）由写门禁按请求上下文
 * 注入（归属=当前店铺），转换不负责设置。
 *
 * @author nona9961
 */
@Component
public class ProductImageConvertor implements PoConverter<ProductImage, ProductImagePO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductImage> domainClass() {
        return ProductImage.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductImagePO> poClass() {
        return ProductImagePO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳与租户列由持久化层填充，转换不负责。
     */
    @Override
    public ProductImagePO toPO(ProductImage domain) {
        final ProductImagePO po = new ProductImagePO();
        po.setId(domain.getId());
        po.setProductId(domain.getProductId());
        po.setUrl(domain.getUrl());
        po.setPrimary(domain.isPrimary());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProductImage toDomain(ProductImagePO po) {
        return new ProductImage(po.getId(), po.getProductId(), po.getUrl(), po.isPrimary());
    }
}