package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.inventory.entity.InventoryLogType;
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

    /**
     * 幂等键存在性判定：(order_id, sku_id, type) 三元组是否已有一行流水
     * （租户过滤内校验——跨店铺请求按不存在呈现，fail-closed）。
     * <p>
     * 重复请求先经本判定快速拒绝（并发窗口内的重复追加由表级唯一
     * 约束兜底，兜底路径的异常在实现层转换为业务异常——两层防线）。
     *
     * @param orderId 订单 ID（订单驱动型必填）
     * @param skuId   归属 SKU ID
     * @param type    流水类型
     * @return 幂等键已存在返回 true
     */
    boolean existsByOrderIdAndSkuIdAndType(Long orderId, Long skuId, InventoryLogType type);
}