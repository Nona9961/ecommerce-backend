package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存门面实现：跨上下文库存契约的最小落地形态（本阶段实现查询面，
 * 动作编排属后续阶段——签名随冻结声明、行为未实现）。
 * <p>
 * {@link #queryAvailable}——多 SKU 可售量查询：按请求 SKU 逐一经仓储
 * 读取（租户过滤 fail-closed——跨店铺/未初始化 SKU 按可售 0 呈现，不
 * 泄露归属），请求集合去重后 zip 语义全量返回（调用方无需区分缺失与
 * 零）。事务边界：本阶段为查询面（只读），事务非必需——读由持久化层
 * 访问点自持，编排方事务内调用时随 REQUIRED 语义并入。
 * <p>
 * 动作面（preoccupy/confirmDeduct/rollback/adjust）签名随冻结声明，
 * 行为实现属后续阶段：编排路径（用例事务内：读取聚合 → 变更方法 →
 * 保存聚合 + 追加流水同事务落库）、预占防超卖条件更新接缝均在后置
 * 阶段落地。本阶段聚合变更方法已实现（校验 + 流水构造 + 版本推进），
 * Facade 动作编排即止于此。
 *
 * @author nona9961
 */
@Component
public class InventoryFacadeImpl implements InventoryFacade {

    /**
     * 库存聚合根仓储（SKU 键读取——可售量查询）
     */
    private final InventoryItemRepository inventoryItemRepository;

    /**
     * 构造库存门面实现。
     *
     * @param inventoryItemRepository 库存聚合根仓储
     */
    public InventoryFacadeImpl(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 去重请求 SKU → 逐一读取（租户过滤缺行按 0）→ zip 全量返回。
     */
    @Override
    public List<InventoryAvailable> queryAvailable(List<Long> skuIds) {
        if (skuIds == null) {
            return List.of();
        }
        return skuIds.stream()
                .distinct()
                .map(this::availableOf)
                .toList();
    }

    /**
     * 单 SKU 可售量：库存行存在则取可售，否则按 0（未初始化/跨店铺均
     * 缺行——fail-closed 统一呈现 0，不泄露归属）。
     *
     * @param skuId SKU ID
     * @return 可售量查询结果
     */
    private InventoryAvailable availableOf(Long skuId) {
        final InventoryItem item = inventoryItemRepository.getBySkuId(skuId);
        return new InventoryAvailable(skuId, item == null ? 0 : item.getAvailable());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 动作编排属后续阶段实现（见类注）。
     */
    @Override
    public void preoccupy(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("下单预占编排属后续阶段");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 动作编排属后续阶段实现（见类注）。
     */
    @Override
    public void confirmDeduct(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("支付确认扣减编排属后续阶段");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 动作编排属后续阶段实现（见类注）。
     */
    @Override
    public void rollback(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("预占回滚编排属后续阶段");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 动作编排属后续阶段实现（见类注）。
     */
    @Override
    public void adjust(Long skuId, int delta) {
        throw new UnsupportedOperationException("手工调整编排属后续阶段");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 动作编排属退款编排阶段实现（见类注：动作面签名随冻结声明，
     * 行为实现与预占/扣减/回滚同轨）。
     */
    @Override
    public void restore(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("退款回补编排属后续阶段");
    }
}