package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.inf.context.ThreadContext;
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
     * 库存主表 JPA 仓储（SKU 键查询用——父类 repository 字段为泛型契约类型）
     */
    private final InventoryItemJpaRepository jpaRepository;

    /**
     * 构造库存仓储。
     *
     * @param repository            库存主表 JPA 仓储
     * @param threadContext         请求级上下文（变更追踪器与快照）
     * @param convertor             库存聚合转换器
     * @param changeTrackerProvider 变更追踪器提供者
     */
    public InventoryItemRepositoryImpl(InventoryItemJpaRepository repository,
                                       ThreadContext threadContext,
                                       InventoryItemConvertor convertor,
                                       ChangeTrackerProvider changeTrackerProvider) {
        super(repository, threadContext, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
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