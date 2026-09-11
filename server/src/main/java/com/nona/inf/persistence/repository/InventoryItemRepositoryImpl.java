package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.converters.InventoryItemConvertor;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

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
     * 提权工具（提权保存前显式租户归属定型：TenantWriteGate 提权+空归属
     * fail-closed 拒绝——库存行 tenant=shopId 归属必得，不依赖请求上下文；
     * 同 SubOrderRepositoryImpl 缺口①修复形态）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 构造库存仓储。
     *
     * @param repository            库存主表 JPA 仓储
     * @param convertor             库存聚合转换器
     * @param changeTrackerProvider 变更追踪器提供者
     * @param tenantContextAccessor 租户上下文读取器（CAS 租户条件注入）
     * @param tenantPrivilege       提权工具（提权保存显式租户归属定型）
     */
    public InventoryItemRepositoryImpl(InventoryItemJpaRepository repository,
                                       InventoryItemConvertor convertor,
                                       ChangeTrackerProvider changeTrackerProvider,
                                       TenantContextAccessor tenantContextAccessor,
                                       TenantPrivilege tenantPrivilege) {
        super(repository, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
        this.tenantContextAccessor = tenantContextAccessor;
        this.tenantPrivilege = tenantPrivilege;
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
        repository.save(ownedBy(convertor.convertToPO(root), root));
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
        repository.save(ownedBy(convertor.convertToPO(root), root));
    }

    /**
     * 根行租户承载：提权写路径（买家取消回滚/支付确认扣减/退款回补等
     * 无请求视角上下文推进店铺数据）显式锚定 tenant=shopId——归属必得，
     * 不依赖请求上下文（提权写门禁语义：TenantWriteGate 提权+空
     * 归属 fail-closed 拒绝）；非提权商家路径保持既有注入语义（行租户由
     * 写门禁按请求上下文注入）。SubOrderRepositoryImpl ownedBy 同先例
     * 形态。
     *
     * @param po   行 PO
     * @param root 库存聚合根（租户锚点）
     * @param <T>  行 PO 类型
     * @return 承载租户后的 PO
     */
    private <T extends TenantScopedBasePO> T ownedBy(T po, InventoryItem root) {
        if (tenantPrivilege.isActive()) {
            po.setTenantID(String.valueOf(root.getShopId()));
        }
        return po;
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
     * {@inheritDoc}
     * <p>
     * 条件更新退款回补（条件更新接缝——业务量条件 sold >= quantity
     * 基于 DB 当前值判定，版本逐笔算术 +1 不参与条件；租户条件从请求
     * 上下文读取并显式注入 WHERE，上下文缺失按 fail-closed 拒绝（提权
     * 段内豁免：按主键全局唯一定位，见 {@link #requiredTenantId()}）；
     * 受影响行数 1=命中推进、0=已售不足/行不存在/跨店铺）。
     */
    @Override
    public int casRestore(Long itemId, int quantity) {
        return jpaRepository.casRestore(itemId, quantity, requiredTenantId());
    }

    /**
     * 读取当前请求租户作为条件更新注入值；上下文缺失按 fail-closed
     * 拒绝（不执行更新——跨店铺条件更新与视角缺失同语义拒绝）。
     * <p>
     * <b>提权豁免（无请求视角上下文面）</b>：买家取消回滚/支付
     * 确认扣减/退款回补/超时调度等提权段内无请求租户——条件更新按
     * 「主键全局唯一」定位（Snowflake 主键跨店无碰撞，不注入租户条件），
     * 跨店语义由用例层归属校验 + 提权写门禁承载；sub_order 超时
     * claim/clear 同先例形态。
     *
     * @return 当前请求租户 ID；提权段内上下文缺失时返回 {@code null}
     *         （按主键定位信号，CAS SQL 展开为租户条件放宽）
     */
    private String requiredTenantId() {
        final String tenantId = tenantContextAccessor.getTenantID();
        if (tenantId != null) {
            return tenantId;
        }
        if (tenantPrivilege.isActive()) {
            return null;
        }
        throw new BusinessException(
                EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                "租户上下文缺失，库存条件更新拒绝执行");
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

    /**
     * {@inheritDoc}
     * <p>
     * 店铺库存分页（商家库存列表页查询面，契约冻结）：当前
     * 租户店铺全集，主键 ID 升序稳定分页；参数守卫 offset&lt;0 /
     * limit&lt;=0 拒绝；分页行未登记变更追踪（只读呈现）。
     */
    @Override
    public List<InventoryItem> listPaged(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负：" + offset);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正：" + limit);
        }
        return jpaRepository.findAll(PageRequest.of(offset / limit, limit, Sort.by("id")))
                .map(po -> convertor.convertToRoot(po, null))
                .stream()
                .toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 店铺库存总数（分页 total 用，当前租户店铺全集）。
     */
    @Override
    public long count() {
        return jpaRepository.count();
    }
}