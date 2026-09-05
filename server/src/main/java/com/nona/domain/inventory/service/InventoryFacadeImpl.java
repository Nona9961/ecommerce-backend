package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存门面实现：跨上下文库存契约的落地形态——查询面已实现；动作面
 * （preoccupy/confirmDeduct/rollback/adjust/restore）签名随冻结声明、
 * 门面接线未落地（域内编排由应用层用例承载，本类动作方法保持契约
 * 冻结占位形态）。
 * <p>
 * {@link #queryAvailable}——多 SKU 可售量查询：按请求 SKU 逐一经仓储
 * 读取（租户过滤 fail-closed——跨店铺/未初始化 SKU 按可售 0 呈现，不
 * 泄露归属），请求集合去重后 zip 语义全量返回（调用方无需区分缺失与
 * 零）。事务边界：本类为查询面（只读），事务非必需——读由持久化层
 * 访问点自持，编排方事务内调用时随 REQUIRED 语义并入。
 * <p>
 * 动作面（preoccupy/confirmDeduct/rollback/adjust/restore）签名随冻结
 * 声明、门面接线未落地：域内编排（用例事务内：定位加载聚合 → 仓储
 * 条件更新 → 聚合变更方法产流水 → 追加流水 + 事件判定同事务落库）
 * 已由应用层用例承载（库存保留用例承载订单驱动四操作、商家端库存
 * 用例承载手工调整）；聚合变更方法与条件更新接缝均已实现，本类动作
 * 方法保持契约冻结占位（UnsupportedOperationException）待消费方接线。
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
     * 门面动作面接线未落地——抛契约冻结占位异常（见类注）。
     */
    @Override
    public void preoccupy(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("下单预占门面接线未落地");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 门面动作面接线未落地——抛契约冻结占位异常（见类注）。
     */
    @Override
    public void confirmDeduct(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("支付确认扣减门面接线未落地");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 门面动作面接线未落地——抛契约冻结占位异常（见类注）。
     */
    @Override
    public void rollback(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("预占回滚门面接线未落地");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 门面动作面接线未落地——抛契约冻结占位异常（见类注）。
     */
    @Override
    public void adjust(Long skuId, int delta) {
        throw new UnsupportedOperationException("手工调整门面接线未落地");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 门面动作面接线未落地——抛契约冻结占位异常（与预占/扣减/回滚
     * 动作面成员同冻结语义，见类注）。
     */
    @Override
    public void restore(Long orderId, List<StockChangeItem> items) {
        throw new UnsupportedOperationException("退款回补门面接线未落地");
    }
}