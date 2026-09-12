package com.nona.inf.persistence.converters;

import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.inf.persistence.po.catalog.FreightTemplatePO;
import org.springframework.stereotype.Component;

/**
 * 运费模板聚合根 ↔ 运费模板 PO 转换器（freight_template 表单行映射，
 * 字段一一对应，无子对象）。
 * <p>
 * 转换经聚合构造器重建领域对象——计费参数按规则归一化与校验在同一
 * 收敛点执行（加载路径对持久化脏参数同样归一整定）；租户列由写门禁
 * 按请求上下文注入（归属=当前店铺），转换不负责设置。
 *
 * @author nona9961
 */
@Component
public class FreightTemplateConvertor extends AbstractConvertor<FreightTemplate, FreightTemplatePO, Void> {

    /**
     * {@inheritDoc}
     */
    @Override
    protected FreightTemplatePO safedConvertToPO(FreightTemplate root) {
        final FreightTemplatePO po = new FreightTemplatePO();
        po.setId(root.getId());
        po.setShopId(root.getShopId());
        po.setName(root.getName());
        po.setRuleType(root.getRuleType());
        po.setPerItemPrice(root.getPerItemPrice());
        po.setBaseFreight(root.getBaseFreight());
        po.setFreeThreshold(root.getFreeThreshold());
        po.setStatus(root.getStatus());
        po.setIsDefault(root.isDefault());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FreightTemplate safedConvertToRoot(FreightTemplatePO po, Void other) {
        return new FreightTemplate(po.getId(), po.getShopId(), po.getName(), po.getRuleType(),
                po.getPerItemPrice(), po.getBaseFreight(), po.getFreeThreshold(), po.getStatus(),
                Boolean.TRUE.equals(po.getIsDefault()));
    }
}