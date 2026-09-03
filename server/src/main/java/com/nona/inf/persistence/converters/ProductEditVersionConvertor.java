package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.inf.persistence.po.catalog.ProductEditVersionPO;
import org.springframework.stereotype.Component;

/**
 * 商品编辑版本实体 ↔ PO 转换器（product_edit_version 表单行映射，字段
 * 一一对应——行级读写，参照 {@link ProductImageConvertor} 简单实体转换器
 * 契约）。
 * <p>
 * 租户列（tenant_id）由写门禁按请求上下文注入（归属=当前店铺），转换不
 * 负责设置；版本产生时间（created_at）由持久化审计填充——toDomain 时
 * 映射到实体创建时间字段（读回路径），toPO 不设置（新建路径由审计
 * 自动填充）。
 *
 * @author nona9961
 */
@Component
public class ProductEditVersionConvertor implements PoConverter<ProductEditVersion, ProductEditVersionPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductEditVersion> domainClass() {
        return ProductEditVersion.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ProductEditVersionPO> poClass() {
        return ProductEditVersionPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳与租户列由持久化层填充，转换不负责。
     */
    @Override
    public ProductEditVersionPO toPO(ProductEditVersion domain) {
        final ProductEditVersionPO po = new ProductEditVersionPO();
        po.setId(domain.getId());
        po.setProductId(domain.getProductId());
        po.setVersionNo(domain.getVersionNo());
        po.setSnapshotJson(domain.getSnapshotJson());
        po.setOperator(domain.getOperator());
        po.setTriggerType(domain.getTriggerType());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 版本产生时间取审计 create_time（读回路径）。
     */
    @Override
    public ProductEditVersion toDomain(ProductEditVersionPO po) {
        return new ProductEditVersion(po.getId(), po.getProductId(), po.getVersionNo(),
                po.getSnapshotJson(), po.getOperator(), po.getTriggerType(), po.getCreateTime());
    }
}