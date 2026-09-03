package com.nona.domain.inventory.repo;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.persistence.BaseRepository;

/**
 * 库存聚合根仓储接口（inventory_item 主表持久化契约，实现在基础设施层）。
 * <p>
 * inventory_item 表 tenant=shopId：读经租户过滤（fail-closed——跨店铺
 * SKU 库存按不存在呈现，归属不泄露）；写出由写门禁按请求上下文注入
 * tenant（商家请求 tenant=当前店铺）。sku_id 为业务唯一键（唯一约束兜底
 * 重复初始化冲突）。乐观锁 version 列随变更递增推进（读回值经聚合构造
 * 守卫非负，变更路径推进规则见 {@link InventoryItem}）。
 * <p>
 * 删除语义：库存行随 SKU 生命周期整体管理（本阶段无删除场景），
 * 基类 delete/deleteByID 不承载业务删除路径。
 *
 * @author nona9961
 */
public interface InventoryItemRepository extends BaseRepository<Long, InventoryItem> {

    /**
     * 按 SKU 业务键取库存聚合。
     *
     * @param skuId 归属 SKU ID
     * @return 库存聚合；不存在或跨店铺（租户过滤）返回 null
     */
    InventoryItem getBySkuId(Long skuId);
}