package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.Sku;
import com.nona.inf.persistence.po.catalog.SkuPO;
import org.springframework.stereotype.Component;

/**
 * 商品 SKU 实体 ↔ PO 转换器（product_sku 表单行映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：Sku 是商品聚合内的子实体，
 * 行级读写不需要聚合上下文参数。租户列（tenant_id）由写门禁按请求
 * 上下文注入（归属=当前店铺），转换不负责设置。
 *
 * @author nona9961
 */
@Component
public class SkuConvertor implements PoConverter<Sku, SkuPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<Sku> domainClass() {
        return Sku.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<SkuPO> poClass() {
        return SkuPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳与租户列由持久化层填充，转换不负责。
     */
    @Override
    public SkuPO toPO(Sku domain) {
        final SkuPO po = new SkuPO();
        po.setId(domain.getId());
        po.setProductId(domain.getProductId());
        po.setSpecHash(domain.getSpecHash());
        po.setSpecSummary(domain.getSpecSummary());
        po.setPrice(domain.getPrice());
        po.setEnabled(domain.isEnabled());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Sku toDomain(SkuPO po) {
        return new Sku(po.getId(), po.getProductId(), po.getSpecHash(),
                po.getSpecSummary(), po.getPrice(), po.isEnabled());
    }
}