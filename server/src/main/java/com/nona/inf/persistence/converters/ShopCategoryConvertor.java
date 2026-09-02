package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.ShopCategory;
import com.nona.inf.persistence.po.catalog.ShopCategoryPO;
import org.springframework.stereotype.Component;

/**
 * 店铺分类实体 ↔ 店铺分类 PO 转换器（shop_category 表单行映射，字段一一对应）。
 * <p>
 * 使用简单实体转换器契约（PoConverter）：ShopCategory 是店铺聚合内的子实体，
 * 行级读写不需要聚合上下文参数。租户列（tenant_id）由写门禁按请求上下文
 * 注入（归属=当前店铺），转换不负责设置。
 *
 * @author nona9961
 */
@Component
public class ShopCategoryConvertor implements PoConverter<ShopCategory, ShopCategoryPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ShopCategory> domainClass() {
        return ShopCategory.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ShopCategoryPO> poClass() {
        return ShopCategoryPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳与租户列由持久化层填充，转换不负责。
     */
    @Override
    public ShopCategoryPO toPO(ShopCategory domain) {
        final ShopCategoryPO po = new ShopCategoryPO();
        po.setId(domain.getId());
        po.setShopId(domain.getShopId());
        po.setName(domain.getName());
        po.setOrderNo(domain.getOrder());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ShopCategory toDomain(ShopCategoryPO po) {
        return new ShopCategory(po.getId(), po.getShopId(), po.getName(), po.getOrderNo());
    }
}