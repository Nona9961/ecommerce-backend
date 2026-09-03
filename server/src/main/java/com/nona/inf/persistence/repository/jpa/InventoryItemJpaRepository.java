package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 条件更新预占（防超卖持久化防线——单语句原子）：可售 −= demand、
     * 预占 += demand、版本 += 1，WHERE 以 {@code available >= demand}
     * 为业务量条件（基于 DB 当前值判定，行锁串行化下恰好 M 份库存只
     * 放行 M 笔预占）。SET 一律基于 DB 当前值算术推进（禁止快照值覆盖
     * 形态——不同操作类型并发交错时破坏三态恒非负不变量）；version 随
     * 更新算术 +1，不参与条件判定（冲突检测辅助列）。租户条件显式
     * 注入：参数由实现层从请求上下文读取（fail-closed），不接收调用方
     * 传入的租户条件。
     *
     * @param itemId   库存聚合根 ID
     * @param demand   预占数量（必须为正，由聚合前置守卫先行校验）
     * @param tenantId 当前请求租户（实现层从请求上下文读取注入）
     * @return 受影响行数（1=命中并推进；0=容量不足或行不存在或跨店铺）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InventoryItemPO p set p.available = p.available - :demand, "
            + "p.held = p.held + :demand, p.version = p.version + 1 "
            + "where p.id = :itemId and p.available >= :demand and p.tenantID = :tenantId")
    int casPreoccupy(@Param("itemId") Long itemId, @Param("demand") int demand,
                     @Param("tenantId") String tenantId);

    /**
     * 条件更新确认扣减（防扣减超预占）：预占 −= quantity、已售 +=
     * quantity、版本 += 1，WHERE 以 {@code held >= quantity} 为业务量
     * 条件。形态约定同 {@link #casPreoccupy(Long, int, String)}（SET 算术
     * 推进、version 不参与判定、租户条件显式注入）。
     *
     * @param itemId   库存聚合根 ID
     * @param quantity 扣减数量（必须为正）
     * @param tenantId 当前请求租户（实现层从请求上下文读取注入）
     * @return 受影响行数（1=命中并推进；0=预占不足或行不存在或跨店铺）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InventoryItemPO p set p.held = p.held - :quantity, "
            + "p.sold = p.sold + :quantity, p.version = p.version + 1 "
            + "where p.id = :itemId and p.held >= :quantity and p.tenantID = :tenantId")
    int casConfirmDeduct(@Param("itemId") Long itemId, @Param("quantity") int quantity,
                         @Param("tenantId") String tenantId);

    /**
     * 条件更新预占回滚（防回滚超预占）：预占 −= quantity、可售 +=
     * quantity、版本 += 1，WHERE 以 {@code held >= quantity} 为业务量
     * 条件。形态约定同 {@link #casPreoccupy(Long, int, String)}。
     *
     * @param itemId   库存聚合根 ID
     * @param quantity 回滚数量（必须为正）
     * @param tenantId 当前请求租户（实现层从请求上下文读取注入）
     * @return 受影响行数（1=命中并推进；0=预占不足或行不存在或跨店铺）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InventoryItemPO p set p.held = p.held - :quantity, "
            + "p.available = p.available + :quantity, p.version = p.version + 1 "
            + "where p.id = :itemId and p.held >= :quantity and p.tenantID = :tenantId")
    int casRollback(@Param("itemId") Long itemId, @Param("quantity") int quantity,
                    @Param("tenantId") String tenantId);
}