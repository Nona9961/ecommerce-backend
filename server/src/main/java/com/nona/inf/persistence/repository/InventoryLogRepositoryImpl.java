package com.nona.inf.persistence.repository;

import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.repo.InventoryLogRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.converters.InventoryLogConvertor;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.List;

/**
 * 库存流水仓储落地：append-only 记录行仓储（inventory_log 表，
 * tenant=shopId），插行与按 SKU 分页查询的机械装配（领域语义——流水由
 * 聚合变更方法内嵌构造——收敛在 InventoryItem 变更方法，本仓储仅承载
 * append 落库与最小查询面）。
 * <p>
 * 读：流水行经租户过滤加载（fail-closed，跨店铺 SKU 流水行不可见）；
 * 写：tenant 列由写门禁按请求上下文注入（商家请求 tenant=当前店铺）。
 * 流水行只增不改：行级删除语义不存在，delete/deleteByID 一律拒绝
 * （UnsupportedOperationException，与商品编辑版本仓储同定式）；幂等键
 * (order_id, sku_id, type) 唯一性由表级约束兜底并发重复追加。
 *
 * @author nona9961
 */
@Component
public class InventoryLogRepositoryImpl implements InventoryLogRepository {

    /**
     * 幂等键唯一约束名（inventory_log 表 uk_inventory_log_order_sku_type，
     * 兜底异常转换的精确匹配基准）
     */
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "UK_INVENTORY_LOG_ORDER_SKU_TYPE";

    /**
     * 流水表 JPA 仓储
     */
    private final InventoryLogJpaRepository jpaRepository;

    /**
     * 流水行转换器
     */
    private final InventoryLogConvertor convertor;

    /**
     * 提权工具（提权保存前显式租户归属定型：TenantWriteGate 提权+空归属
     * fail-closed 拒绝——流水行 tenant=shopId 归属必得，不依赖请求上下文；
     * SubOrderRepositoryImpl ownedBy 同先例形态）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 构造流水仓储。
     *
     * @param jpaRepository 流水表 JPA 仓储
     * @param convertor     流水行转换器
     * @param tenantPrivilege 提权工具（提权保存显式租户归属定型）
     */
    public InventoryLogRepositoryImpl(InventoryLogJpaRepository jpaRepository,
                                      InventoryLogConvertor convertor,
                                      TenantPrivilege tenantPrivilege) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
        this.tenantPrivilege = tenantPrivilege;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 追加插入（append-only：save 即插一行，无更新路径）；租户归属由写
     * 门禁按请求上下文注入，提权写路径（买家/回调/调度上下文）经
     * {@link #ownedBy} 显式锚定 tenant=shopId。同一 (order_id, sku_id,
     * type) 重复追加被 DB 唯一约束拒绝——约束冲突按幂等键约束名精确判
     * 定后转换为业务异常（重复变更请求 409），其余约束异常保持原样上
     * 抛（不遮其他异常）。
     */
    @Override
    public InventoryLog append(InventoryLog log) {
        final InventoryLogPO po = ownedBy(convertor.toPO(log), log);
        try {
            return convertor.toDomain(jpaRepository.save(po));
        } catch (final DataIntegrityViolationException ex) {
            final BusinessException duplicate = asIdempotencyKeyDuplicate(ex);
            if (duplicate != null) {
                throw duplicate;
            }
            throw ex;
        }
    }

    /**
     * 流水行租户承载：提权写路径（买家取消回滚/支付确认扣减/退款回补等
     * 无请求视角上下文）显式锚定 tenant=shopId——归属必得，不依赖请求
     * 上下文（提权写门禁语义：TenantWriteGate 提权+空归属
     * fail-closed 拒绝）；非提权商家路径保持既有注入语义（行租户由写门
     * 禁按请求上下文注入）。
     *
     * @param po  流水行 PO
     * @param log 流水聚合（租户锚点）
     * @param <T> 行 PO 类型
     * @return 承载租户后的 PO
     */
    private <T extends TenantScopedBasePO> T ownedBy(T po, InventoryLog log) {
        if (tenantPrivilege.isActive()) {
            po.setTenantID(String.valueOf(log.getShopId()));
        }
        return po;
    }

    /**
     * 幂等键约束冲突识别：沿异常链定位数据库约束违反，按幂等键约束名
     * 精确判定——命中转换为重复变更请求业务异常；否则返回 null
     * （原异常上抛，不遮其他约束异常）。
     *
     * @param ex 数据完整性违反异常
     * @return 转换后的业务异常；非幂等键冲突返回 null
     */
    private BusinessException asIdempotencyKeyDuplicate(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                final String constraintName = violation.getConstraintName();
                if (constraintName != null
                        && constraintName.toUpperCase(Locale.ROOT).contains(IDEMPOTENCY_KEY_CONSTRAINT)) {
                    return new BusinessException(
                            EcommerceBusinessCode.INVENTORY_LOG_DUPLICATE.code(),
                            "重复变更请求：同订单同 SKU 同类型的库存变动已存在");
                }
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 列表行未登记变更追踪（只读呈现，append-only 行无更新路径）。分页
     * 参数与既有仓储同形态（offset/limit → 页码换算）。
     */
    @Override
    public List<InventoryLog> listBySkuPaged(Long skuId, int offset, int limit) {
        final Page<InventoryLogPO> page = jpaRepository.findBySkuIdOrderByIdDesc(
                skuId, PageRequest.of(offset / limit, limit));
        return page.stream().map(convertor::toDomain).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countBySku(Long skuId) {
        return jpaRepository.countBySkuId(skuId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按行 ID 取流水行（行级读取，基类契约完备；业务查询面为 SKU 维度
     * 分页接口）。
     */
    @Override
    public InventoryLog getByID(Long id) {
        return jpaRepository.findById(id).map(convertor::toDomain).orElse(null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 追加插入（append-only：save 即插一行，与 {@link #append} 同路径）。
     */
    @Override
    public boolean save(InventoryLog log) {
        jpaRepository.save(convertor.toPO(log));
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 幂等键存在性判定：委托 JPA 派生查询（租户过滤内判定，跨店铺
     * 请求按不存在呈现，fail-closed）；重复请求先经本判定快速拒绝，
     * 并发窗口由流水表唯一约束兜底（append 路径异常转换）。
     */
    @Override
    public boolean existsByIdempotencyKey(Long orderId, Long skuId, InventoryLogType type) {
        return jpaRepository.existsByOrderIdAndSkuIdAndType(orderId, skuId, type);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 本仓储无行级删除语义（append-only 流水链）：行级删除一律拒绝。
     */
    @Override
    public int delete(InventoryLog log) {
        throw new UnsupportedOperationException("流水行禁止行级删除（append-only，随库存生命周期留存）");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 行级删除语义不存在（见 {@link #delete(InventoryLog)}）。
     */
    @Override
    public int deleteByID(Long id) {
        throw new UnsupportedOperationException("流水行禁止行级删除（append-only，随库存生命周期留存）");
    }
}