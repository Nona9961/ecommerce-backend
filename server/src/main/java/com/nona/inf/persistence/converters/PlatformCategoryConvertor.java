package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.PlatformCategory;
import com.nona.inf.persistence.po.catalog.PlatformCategoryPO;
import org.springframework.stereotype.Component;

/**
 * 平台分类实体 ↔ 平台分类 PO 转换器（platform_category 单表映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：PlatformCategory 为单表聚合根，
 * 行级读写不需要聚合上下文参数。表 global 无租户列，转换不涉及租户处理；
 * 审计时间戳由 JPA auditing 填充。
 *
 * @author nona9961
 */
@Component
public class PlatformCategoryConvertor implements PoConverter<PlatformCategory, PlatformCategoryPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<PlatformCategory> domainClass() {
        return PlatformCategory.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<PlatformCategoryPO> poClass() {
        return PlatformCategoryPO.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformCategoryPO toPO(PlatformCategory domain) {
        final PlatformCategoryPO po = new PlatformCategoryPO();
        po.setId(domain.getId());
        po.setName(domain.getName());
        po.setOrderNo(domain.getOrder());
        po.setStatus(domain.getStatus());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformCategory toDomain(PlatformCategoryPO po) {
        return new PlatformCategory(po.getId(), po.getName(), po.getOrderNo(), po.getStatus());
    }
}