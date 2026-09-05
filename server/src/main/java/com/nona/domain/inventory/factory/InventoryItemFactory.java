package com.nona.domain.inventory.factory;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 库存聚合根工厂：SKU 库存的创建入口（行 ID 生成 + 三态清零定型）。
 * <p>
 * 创建语义：SKU 关联库存初始化——三态清零、乐观锁版本 0；创建本身无
 * 变更语义，不产生流水（此后一切数量变化经聚合变更方法并随流水落库）。
 * 初始化触发由商家建档流程显式调用（本工厂为创建能力入口；查询未初始
 * 化 SKU 按可售 0 呈现、不懒建）。重复初始化由
 * inventory_item.sku_id 唯一约束拒绝（业务冲突）。
 * <p>
 * 装配约束（构造路径校验，与仓储加载共用）：归属店铺与 SKU 必填——
 * 非法归属的库存行无业务意义。
 *
 * @author nona9961
 */
@Component
public class InventoryItemFactory {

    /**
     * 创建 SKU 库存（三态清零，乐观锁版本 0）。
     *
     * @param shopId 归属店铺 ID（必填）
     * @param skuId  归属 SKU ID（必填）
     * @return 新建库存聚合（三态 0 / 版本 0，待仓储保存）
     */
    public InventoryItem createInitial(Long shopId, Long skuId) {
        return new InventoryItem(IDUtils.generateID(), shopId, skuId, 0, 0, 0, 0);
    }
}