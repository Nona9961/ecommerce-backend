package com.nona.inf.persistence.converters;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import org.springframework.stereotype.Component;

/**
 * 库存聚合根 ↔ 库存 PO 转换器（inventory_item 表单行映射，字段一一对应，
 * 无子对象——流水独立成表不经本转换器装配）。
 * <p>
 * 转换经聚合构造器重建领域对象——三态非负/版本非负/归属齐备的形状守卫
 * 在同一收敛点执行（加载路径对持久化脏参数同样拒装）；shop_id 不冗余
 * 落列，店铺归属即租户列（tenant_id=shopId）——读回时租户列解析为店铺
 * ID（店铺 ID 即租户 ID，String 承载 Long 无精度损失：Snowflake ID 在
 * Long 全量范围内精确）。写路径租户列由写门禁按请求上下文注入（商家
 * 请求 tenant=当前店铺），转换不负责设置。
 *
 * @author nona9961
 */
@Component
public class InventoryItemConvertor extends AbstractConvertor<InventoryItem, InventoryItemPO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 租户列由写门禁注入，转换不设置（本表不冗余 shop_id 列）。
     */
    @Override
    protected InventoryItemPO safedConvertToPO(InventoryItem root) {
        final InventoryItemPO po = new InventoryItemPO();
        po.setId(root.getId());
        po.setSkuId(root.getSkuId());
        po.setAvailable(root.getAvailable());
        po.setHeld(root.getHeld());
        po.setSold(root.getSold());
        po.setVersion(root.getVersion());
        return po;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 三态与版本读回经聚合构造守卫复检（负值/畸形行拒绝装配）。
     */
    @Override
    protected InventoryItem safedConvertToRoot(InventoryItemPO po, Void other) {
        return new InventoryItem(po.getId(), Long.valueOf(po.getTenantID()), po.getSkuId(),
                po.getAvailable(), po.getHeld(), po.getSold(), po.getVersion());
    }
}