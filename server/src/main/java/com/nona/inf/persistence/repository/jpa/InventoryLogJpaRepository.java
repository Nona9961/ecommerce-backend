package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 库存流水 JPA 仓储（inventory_log 表，InventoryItem 聚合的 append-only
 * 从表行，tenant=shopId）。
 * <p>
 * 查询经租户过滤（tenant_id=context）仅命中本店铺流水行——跨店铺 SKU
 * 流水按不存在呈现（fail-closed）。幂等键 (order_id, sku_id, type)
 * 唯一性由表级约束兜底（uk_inventory_log_order_sku_type，并发重复插入
 * 拒绝）；流水行只增不改（本仓储无更新/删除路径，行级删除语义不存在）。
 *
 * @author nona9961
 */
public interface InventoryLogJpaRepository extends JpaRepository<InventoryLogPO, Long> {

    /**
     * 按归属 SKU 分页查流水（新行在前——ID 倒序，Snowflake ID 单调递增
     * 近似产生时间序）。
     *
     * @param skuId    SKU ID
     * @param pageable 分页参数
     * @return 流水分页结果
     */
    Page<InventoryLogPO> findBySkuIdOrderByIdDesc(Long skuId, Pageable pageable);

    /**
     * 按归属 SKU 统计流水数（分页 total 用）。
     *
     * @param skuId SKU ID
     * @return 流水数
     */
    long countBySkuId(Long skuId);
}