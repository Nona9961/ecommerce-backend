package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 库存聚合根 JPA 仓储（inventory_item 表，InventoryItem 聚合根主表，
 * tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺库存行——跨店铺 SKU
 * 库存按不存在呈现（fail-closed，归属不泄露）。sku_id 业务唯一性由
 * 表级约束兜底（uk_inventory_item_sku，并发重复初始化拒绝——直插第二
 * 行抛 {@link org.springframework.dao.DataIntegrityViolationException}，
 * 首个写入行保持）。
 *
 * @author nona9961
 */
public interface InventoryItemJpaRepository extends JpaRepository<InventoryItemPO, Long> {

    /**
     * 按 SKU 业务键取库存行（可售量查询/初始化查重共用；跨店铺 SKU 在
     * 租户过滤层即返回空）。
     *
     * @param skuId SKU ID
     * @return 库存行；不存在或跨店铺返回空
     */
    Optional<InventoryItemPO> findBySkuId(Long skuId);
}