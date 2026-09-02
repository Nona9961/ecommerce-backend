package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Brand;
import com.nona.inf.persistence.po.catalog.BrandPO;
import org.springframework.stereotype.Component;

/**
 * 品牌实体 ↔ 品牌 PO 转换器（brand 单表映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：Brand 为单表聚合根，
 * 行级读写不需要聚合上下文参数。表 global 无租户列，转换不涉及租户处理；
 * 审计时间戳由 JPA auditing 填充。
 *
 * @author nona9961
 */
@Component
public class BrandConvertor implements PoConverter<Brand, BrandPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<Brand> domainClass() {
        return Brand.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<BrandPO> poClass() {
        return BrandPO.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BrandPO toPO(Brand domain) {
        final BrandPO po = new BrandPO();
        po.setId(domain.getId());
        po.setName(domain.getName());
        po.setLogo(domain.getLogo());
        po.setStatus(domain.getStatus());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Brand toDomain(BrandPO po) {
        return new Brand(po.getId(), po.getName(), po.getLogo(), po.getStatus());
    }
}