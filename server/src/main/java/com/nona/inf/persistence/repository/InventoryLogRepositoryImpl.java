package com.nona.inf.persistence.repository;

import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.repo.InventoryLogRepository;
import com.nona.inf.persistence.converters.InventoryLogConvertor;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

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
     * 流水表 JPA 仓储
     */
    private final InventoryLogJpaRepository jpaRepository;

    /**
     * 流水行转换器
     */
    private final InventoryLogConvertor convertor;

    /**
     * 构造流水仓储。
     *
     * @param jpaRepository 流水表 JPA 仓储
     * @param convertor     流水行转换器
     */
    public InventoryLogRepositoryImpl(InventoryLogJpaRepository jpaRepository,
                                      InventoryLogConvertor convertor) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 追加插入（append-only：save 即插一行，无更新路径）；租户归属由写
     * 门禁按请求上下文注入。同一 (order_id, sku_id, type) 重复追加被
     * DB 唯一约束拒绝。
     */
    @Override
    public InventoryLog append(InventoryLog log) {
        return convertor.toDomain(jpaRepository.save(convertor.toPO(log)));
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