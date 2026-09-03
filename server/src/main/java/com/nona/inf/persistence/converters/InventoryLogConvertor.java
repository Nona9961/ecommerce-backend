package com.nona.inf.persistence.converters;

import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import org.springframework.stereotype.Component;

/**
 * 库存流水实体 ↔ PO 转换器（inventory_log 表单行映射，字段一一对应——
 * 行级读写，参照简单实体转换器契约）。
 * <p>
 * 租户列（tenant_id）由写门禁按请求上下文注入（归属=当前店铺），转换
 * 不负责设置；店铺归属即租户列（tenant_id=shopId），读回时租户列解析
 * 为店铺 ID；流水产生时间（create_time）由持久化审计填充——toDomain
 * 时映射到实体产生时间字段（读回路径），toPO 不设置（新建路径由审计
 * 自动填充）。读回经聚合构造路径复检（前后快照算术自洽/上下文形态与
 * 类型一致——持久化脏行拒绝装配）。
 *
 * @author nona9961
 */
@Component
public class InventoryLogConvertor implements PoConverter<InventoryLog, InventoryLogPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<InventoryLog> domainClass() {
        return InventoryLog.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<InventoryLogPO> poClass() {
        return InventoryLogPO.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳与租户列由持久化层填充，转换不负责。
     */
    @Override
    public InventoryLogPO toPO(InventoryLog domain) {
        final InventoryLogPO po = new InventoryLogPO();
        po.setId(domain.getId());
        po.setSkuId(domain.getSkuId());
        po.setType(domain.getType());
        po.setDelta(domain.getDelta());
        po.setOrderId(domain.getOrderId());
        po.setBeforeAvailable(domain.getBeforeAvailable());
        po.setBeforeHeld(domain.getBeforeHeld());
        po.setBeforeSold(domain.getBeforeSold());
        po.setAfterAvailable(domain.getAfterAvailable());
        po.setAfterHeld(domain.getAfterHeld());
        po.setAfterSold(domain.getAfterSold());
        po.setOperator(domain.getOperator());
        po.setReason(domain.getReason());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 流水产生时间取审计 create_time（读回路径）。
     */
    @Override
    public InventoryLog toDomain(InventoryLogPO po) {
        return new InventoryLog(po.getId(), Long.valueOf(po.getTenantID()), po.getSkuId(),
                po.getType(), po.getDelta(), po.getOrderId(),
                po.getBeforeAvailable(), po.getBeforeHeld(), po.getBeforeSold(),
                po.getAfterAvailable(), po.getAfterHeld(), po.getAfterSold(),
                po.getOperator(), po.getReason(), po.getCreateTime());
    }
}