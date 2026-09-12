package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.ports.InventoryAvailable;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存门面实现：跨上下文库存契约的落地形态——查询面（{@link
 * #queryAvailable}）与订单驱动动作面（preoccupy/confirmDeduct/rollback/
 * restore）已接线；手工调整（{@link #adjust}）保持契约冻结占位（域内
 * 无操作人来源，见方法注）。
 * <p>
 * {@link #queryAvailable}——多 SKU 可售量查询：按请求 SKU 逐一经仓储
 * 读取（租户过滤 fail-closed——跨店铺/未初始化 SKU 按可售 0 呈现，不
 * 泄露归属），请求集合去重后 zip 语义全量返回（调用方无需区分缺失与
 * 零）。事务边界：本类为查询面（只读），事务非必需——读由持久化层
 * 访问点自持，编排方事务内调用时随 REQUIRED 语义并入。
 * <p>
 * 动作面（preoccupy/confirmDeduct/rollback/restore）委托库存保留领域
 * 服务（{@link InventoryReservationService}，域内注入合法形态）——逐
 * item 转发（orderId, skuId, quantity）：方法级契约（幂等键/条件更新
 * 语义/流水/事件判定）已由领域服务承载，门面不做重复校验；批量原子性
 * 由编排方（应用层用例）同事务边界保障，本门面逐个 SKU 独立判定；
 * null/空明细按无操作安全返回（与查询面 null 防御同构）。事务边界：
 * 动作面随调用方事务并入（REQUIRED），本类不持事务注解。
 * <p>
 * 手工调整（{@link #adjust}）：签名冻结、保持契约冻结占位——委托面
 * （商家端库存用例 adjustStock）需要店铺/操作人（认证上下文来源），
 * 域内无此来源（本门面无认证上下文、无操作人物化契约），承接方按
 * 「域内编排由商家端用例承载」语义：商家调整走应用层用例直连，门面
 * adjust 不自行发明操作人来源（不触碰审计语义）；待契约演进（门面
 * 签名携带操作人/店铺）后接线。
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
     * 库存保留领域服务（订单驱动四动作委托面）
     */
    private final InventoryReservationService inventoryReservationService;

    /**
     * 构造库存门面实现。
     *
     * @param inventoryItemRepository        库存聚合根仓储
     * @param inventoryReservationService    库存保留领域服务
     */
    public InventoryFacadeImpl(InventoryItemRepository inventoryItemRepository,
                               InventoryReservationService inventoryReservationService) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.inventoryReservationService = inventoryReservationService;
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
     * 委托库存保留领域服务逐 item 预占（幂等键/条件更新语义在领域服务
     * 内承载，门面不重复校验）；null/空明细按无操作安全返回。
     */
    @Override
    public void preoccupy(Long orderId, List<StockChangeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (final StockChangeItem item : items) {
            inventoryReservationService.preoccupy(orderId, item.skuId(), item.quantity());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 委托库存保留领域服务逐 item 确认扣减（幂等键/条件更新语义在领域
     * 服务内承载，门面不重复校验）；null/空明细按无操作安全返回。
     */
    @Override
    public void confirmDeduct(Long orderId, List<StockChangeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (final StockChangeItem item : items) {
            inventoryReservationService.confirmDeduct(orderId, item.skuId(), item.quantity());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 委托库存保留领域服务逐 item 预占回滚（幂等键/条件更新语义在领域
     * 服务内承载，门面不重复校验）；null/空明细按无操作安全返回。
     */
    @Override
    public void rollback(Long orderId, List<StockChangeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (final StockChangeItem item : items) {
            inventoryReservationService.rollback(orderId, item.skuId(), item.quantity());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 契约冻结占位（见类注）：委托面需要店铺/操作人（认证上下文来源），
     * 域内无此来源——保持占位拒绝，不自行发明操作人来源（不触碰审计
     * 语义）；商家调整由应用层商家端用例承载。
     */
    @Override
    public void adjust(Long skuId, int delta) {
        throw new UnsupportedOperationException("手工调整门面接线未落地（操作人来源待契约演进）");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 委托库存保留领域服务逐 item 退款回补（幂等键/条件更新语义在领域
     * 服务内承载，门面不重复校验）；null/空明细按无操作安全返回。
     */
    @Override
    public void restore(Long orderId, List<StockChangeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (final StockChangeItem item : items) {
            inventoryReservationService.restore(orderId, item.skuId(), item.quantity());
        }
    }
}