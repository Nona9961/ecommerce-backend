package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.persistence.converters.InventoryItemConvertor;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.stereotype.Component;

/**
 * 库存仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 inventory_item（tenant=shopId）单表单行，无集合子实体（流水独立
 * 成表，由 InventoryLogRepository 承载）。
 * <p>
 * 读：按主表主键加载（track 快照）；按 SKU 业务键读取为查询路径（不
 * 登记变更追踪——仅供可售量呈现/初始化查重等只读呈现，变更须经
 * getByID 重新加载建立快照基线）。保存：读 → track → calculateChanges →
 * 变更集驱动整行更新（单表聚合无子表可精细化，根行字段覆盖落库）；
 * 删除：deleteByID 返回真实删除条数（0/1 真实语义——库存行随 SKU 生命
 * 周期整体管理，本阶段无删除场景，删除语义按基类契约完备）。
 * <p>
 * 写路径的租户列由写门禁按请求上下文注入（商家请求 tenant=当前店铺），
 * 读路径由 Hibernate 租户过滤保证 fail-closed（跨店铺加载不到库存行，
 * 按不存在呈现）。
 *
 * @author nona9961
 */
@Component
public class InventoryItemRepositoryImpl extends DifferRepository<InventoryItem, InventoryItemPO, Void>
        implements InventoryItemRepository {

    /**
     * 库存主表 JPA 仓储（SKU 键查询/条件更新用——父类 repository 字段为
     * 泛型契约类型）
     */
    private final InventoryItemJpaRepository jpaRepository;

    /**
     * 租户上下文读取器（条件更新的租户条件注入源）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造库存仓储。
     *
     * @param repository            库存主表 JPA 仓储
     * @param convertor             库存聚合转换器
     * @param changeTrackerProvider 变更追踪器提供者
     * @param tenantContextAccessor 租户上下文读取器（CAS 租户条件注入）
     */
    public InventoryItemRepositoryImpl(InventoryItemJpaRepository repository,
                                       InventoryItemConvertor convertor,
                                       ChangeTrackerProvider changeTrackerProvider,
                                       TenantContextAccessor tenantContextAccessor) {
        super(repository, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 库存行 ID。
     */
    @Override
    protected Long retrieveIDFromRoot(InventoryItem root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行（SKU 库存首次落库：初始化入口——三态清零行）。同 SKU
     * 重复插入由表级唯一约束兜底拒绝。
     */
    @Override
    protected void doInsert(InventoryItem root) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表聚合仅有根行字段变更（三态/版本推进）：整行更新主表（JPA
     * merge——不存在时插入、已存在时字段覆盖，避免逐字段映射漂移）。
     * 变更集空判定由模板 save 流程完成（isEmpty 提前返回，本方法仅在
     * 存在变更时被调用）。
     */
    @Override
    protected void doUpdate(InventoryItem root, ChangeSet changeSet) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整行库存：委托 {@link #deleteByID}（按库存行 ID）。
     */
    @Override
    public int delete(InventoryItem item) {
        return item == null ? 0 : deleteByID(item.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除根行，返回真实删除条数（0/1，非契约形）。事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long itemId) {
        if (itemId == null || !repository.existsById(itemId)) {
            return 0;
        }
        repository.deleteById(itemId);
        return 1;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 条件更新预占（条件更新接缝：单语句条件 UPDATE——业务量条件
     * available >= demand 基于 DB 当前值判定，版本逐笔算术 +1 不参与
     * 条件；租户条件从请求上下文读取并显式注入 WHERE，上下文缺失按
     * fail-closed 拒绝；受影响行数 1=命中推进、0=容量不足/行不存在/
     * 跨店铺）。
     */
    @Override
    public int casPreoccupy(Long itemId, int demand) {
        return jpaRepository.casPreoccupy(itemId, demand, requiredTenantId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 条件更新确认扣减（同 {@link #casPreoccupy(Long, int)}，业务量
     * 条件为 held >= quantity）。
     */
    @Override
    public int casConfirmDeduct(Long itemId, int quantity) {
        return jpaRepository.casConfirmDeduct(itemId, quantity, requiredTenantId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 条件更新预占回滚（同 {@link #casPreoccupy(Long, int)}，业务量
     * 条件为 held >= quantity）。
     */
    @Override
    public int casRollback(Long itemId, int quantity) {
        return jpaRepository.casRollback(itemId, quantity, requiredTenantId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 条件更新手工调整可售（条件更新接缝：单语句条件 UPDATE——业务量
     * 条件 available + delta >= 0 基于 DB 当前值判定，版本逐笔算术 +1
     * 不参与条件；租户条件从请求上下文读取并显式注入 WHERE，上下文
     * 缺失按 fail-closed 拒绝；受影响行数 1=命中推进、0=调整致负/行不
     * 存在/跨店铺）。
     */
    @Override
    public int casAdjust(Long itemId, int delta) {
        return jpaRepository.casAdjust(itemId, delta, requiredTenantId());
    }

    /**
     * 读取当前请求租户作为条件更新注入值；上下文缺失按 fail-closed
     * 拒绝（不执行更新——跨店铺条件更新与视角缺失同语义拒绝）。
     *
     * @return 当前请求租户 ID
     */
    private String requiredTenantId() {
        final String tenantId = tenantContextAccessor.getTenantID();
        if (tenantId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                    "租户上下文缺失，库存条件更新拒绝执行");
        }
        return tenantId;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按 SKU 业务键读取（租户过滤共同定位：跨店铺 SKU 返回 null）。
     * 读取未登记变更追踪——只读呈现（可售量查询/初始化查重），如需
     * 变更保存须经 getByID 重新加载建立快照基线。
     */
    @Override
    public InventoryItem getBySkuId(Long skuId) {
        return jpaRepository.findBySkuId(skuId)
                .map(po -> convertor.convertToRoot(po, null))
                .orElse(null);
    }
}