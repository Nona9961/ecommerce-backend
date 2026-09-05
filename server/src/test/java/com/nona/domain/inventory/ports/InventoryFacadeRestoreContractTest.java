package com.nona.domain.inventory.ports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存门面扩展契约冻结测试：退款回补成员（restore）的跨上下文契约钉。
 * <p>
 * 契约语义（冻结声明，行为实现属退款编排阶段——与预占/扣减/回滚动作
 * 面成员同冻结语义）：
 * <ul>
 *     <li>签名形态与既有订单驱动动作同构：订单 ID + 订单驱动单 SKU
 *         变更明细列表（StockChangeItem——SKU + 数量）；</li>
 *     <li>幂等键 (order_id, sku_id, type) 复用：同一订单同一 SKU 的
 *         REFUND_RESTORE 只允许一次；</li>
 *     <li>「已发货/已完成不回补」由编排层按子单状态判定保障（本契约
 *         只承载域能力）；</li>
 *     <li>批量原子性由编排层同事务边界保障（逐个 SKU 独立幂等、独立
 *         判定）。</li>
 * </ul>
 * 反射断言防签名漂移：任何契约调整必须显式走冻结点更新路径，不得
 * 静默改变消费方（退款编排）可见的签名形态。
 *
 * @author nona9961
 */
class InventoryFacadeRestoreContractTest {

    /**
     * 契约接口（库存门面）
     */
    private static final Class<InventoryFacade> CONTRACT = InventoryFacade.class;

    /**
     * 契约冻结：restore 成员（订单 ID + 明细列表）存在且返回 void——
     * 与 preoccupy/confirmDeduct/rollback 动作面同形。
     */
    @Test
    @DisplayName("契约冻结：restore 签名（订单ID+明细列表）存在")
    void restore_contractDeclared() throws Exception {
        final Method restore = CONTRACT.getMethod("restore", Long.class, List.class);
        assertThat(restore.getReturnType()).isEqualTo(void.class);
    }

    /**
     * 契约冻结：明细参数为订单驱动单 SKU 变更项列表（StockChangeItem
     * 类型钉——SKU + 数量语义不漂移）。
     */
    @Test
    @DisplayName("契约冻结：明细参数为订单驱动变更项列表")
    void restore_itemsParameterIsStockChangeList() throws Exception {
        final Method restore = CONTRACT.getMethod("restore", Long.class, List.class);
        assertThat(restore.getGenericParameterTypes()[1].getTypeName())
                .isEqualTo("java.util.List<com.nona.domain.inventory.ports.StockChangeItem>");
    }
}