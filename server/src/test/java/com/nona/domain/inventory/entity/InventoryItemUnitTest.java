package com.nona.domain.inventory.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存聚合根单元测试：三态库存行为面契约（预占/确认/回滚/调整/回补 →
 * 每笔变更必产流水）与装配形态守卫。
 * <p>
 * 行为面现状：变更方法（{@link InventoryItem#preoccupy} 等）均已实现
 * （前置守卫 + 流水构造 + 三态推进 + 版本递增），本文件断言即行为面
 * 契约——「变更携带流水」「三态恰界」「负值守卫」类用例验证实现语义；
 * 装配形态守卫（构造校验/归属定型）同文件覆盖。
 * <p>
 * 断言契约（实现方核对）：
 * <ul>
 *     <li>变更方法返回流水行（type/delta/orderId/before/after 与断言
 *         逐项一致）；聚合三态同步推进；乐观锁版本逐笔 +1；</li>
 *     <li>可售不足（预占超量/调整致负）→ {@link EcommerceBusinessCode#INVENTORY_INSUFFICIENT}；
 *         数量非正 → {@link EcommerceBusinessCode#INVENTORY_QUANTITY_INVALID}；
 *         订单驱动流水缺订单号 → {@link EcommerceBusinessCode#INVENTORY_LOG_INVALID}；</li>
 *     <li>装配形态守卫（三态非负/版本非负/归属定型）→
 *         {@link EcommerceBusinessCode#INVENTORY_ITEM_INVALID}。</li>
 * </ul>
 * 注：测试内以全量构造器装配「读回形态」库存行（三态非零的状态基线），
 * 与仓储读回路径同构；创建路径（三态清零）由工厂测试覆盖。
 *
 * @author nona9961
 */
class InventoryItemUnitTest {

    /**
     * 归属店铺（构造基线）
     */
    private static final long SHOP_ID = 1001L;

    /**
     * 归属 SKU（构造基线）
     */
    private static final long SKU_ID = 88001L;

    /**
     * 目标订单（订单驱动流水基线）
     */
    private static final long ORDER_ID = 660001L;

    /**
     * 操作人（手动调整流水基线）
     */
    private static final String OPERATOR = "nona9961";

    /**
     * happy：预占——可售减、预占增，产出 PREOCCUPY 流水（before/after/
     * delta/orderId 逐项正确）。
     */
    @Test
    @DisplayName("预占推进三态并产出 PREOCCUPY 流水")
    void preoccupy_movesStatesAndProducesLog() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 10, 0, 0, 0);

        final InventoryLog log = item.preoccupy(ORDER_ID, 3);

        assertThat(log.getType()).isEqualTo(InventoryLogType.PREOCCUPY);
        assertThat(log.getDelta()).isEqualTo(3);
        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getBeforeHeld()).isZero();
        assertThat(log.getBeforeSold()).isZero();
        assertThat(log.getAfterAvailable()).isEqualTo(7);
        assertThat(log.getAfterHeld()).isEqualTo(3);
        assertThat(log.getAfterSold()).isZero();
        assertThat(item.getAvailable()).isEqualTo(7);
        assertThat(item.getHeld()).isEqualTo(3);
        assertThat(item.getSold()).isZero();
    }

    /**
     * happy：确认扣减——预占减、已售增，产出 CONFIRM 流水（before/after
     * 仅预占与已售位移）。
     */
    @Test
    @DisplayName("确认扣减推进三态并产出 CONFIRM 流水")
    void confirmDeduct_movesStatesAndProducesLog() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 5, 0, 0);

        final InventoryLog log = item.confirmDeduct(ORDER_ID, 2);

        assertThat(log.getType()).isEqualTo(InventoryLogType.CONFIRM);
        assertThat(log.getDelta()).isEqualTo(2);
        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getBeforeAvailable()).isEqualTo(5);
        assertThat(log.getBeforeHeld()).isEqualTo(5);
        assertThat(log.getAfterHeld()).isEqualTo(3);
        assertThat(log.getAfterSold()).isEqualTo(2);
        assertThat(item.getHeld()).isEqualTo(3);
        assertThat(item.getSold()).isEqualTo(2);
        assertThat(item.getAvailable()).isEqualTo(5);
    }

    /**
     * happy：预占回滚——预占减、可售增，产出 ROLLBACK 流水。
     */
    @Test
    @DisplayName("预占回滚推进三态并产出 ROLLBACK 流水")
    void rollback_movesStatesAndProducesLog() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 5, 0, 0);

        final InventoryLog log = item.rollback(ORDER_ID, 2);

        assertThat(log.getType()).isEqualTo(InventoryLogType.ROLLBACK);
        assertThat(log.getDelta()).isEqualTo(2);
        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getBeforeAvailable()).isEqualTo(5);
        assertThat(log.getBeforeHeld()).isEqualTo(5);
        assertThat(log.getAfterAvailable()).isEqualTo(7);
        assertThat(log.getAfterHeld()).isEqualTo(3);
        assertThat(item.getAvailable()).isEqualTo(7);
        assertThat(item.getHeld()).isEqualTo(3);
    }

    /**
     * happy：手工调整——仅可售变动（负向），预占/已售不动，产出
     * MANUAL_ADJUST 流水（无订单上下文、操作人/原因就位）。
     */
    @Test
    @DisplayName("手工调整仅动可售并产出 MANUAL_ADJUST 流水")
    void adjust_onlyMovesAvailableAndProducesLog() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 10, 0, 0, 0);

        final InventoryLog log = item.adjust(OPERATOR, -4, "压货清理");

        assertThat(log.getType()).isEqualTo(InventoryLogType.MANUAL_ADJUST);
        assertThat(log.getDelta()).isEqualTo(-4);
        assertThat(log.getOrderId()).isNull();
        assertThat(log.getOperator()).isEqualTo(OPERATOR);
        assertThat(log.getReason()).isEqualTo("压货清理");
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getAfterAvailable()).isEqualTo(6);
        assertThat(log.getBeforeHeld()).isZero();
        assertThat(log.getAfterHeld()).isZero();
        assertThat(item.getAvailable()).isEqualTo(6);
        assertThat(item.getHeld()).isZero();
        assertThat(item.getSold()).isZero();
    }

    /**
     * happy：退款回补——已售减、可售增，产出 REFUND_RESTORE 流水
     * （回补语义接缝：未发货退款/发货超时关单回补）。
     */
    @Test
    @DisplayName("退款回补推进三态并产出 REFUND_RESTORE 流水")
    void restoreSold_movesStatesAndProducesLog() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 10, 3, 5, 0);

        final InventoryLog log = item.restoreSold(ORDER_ID, 2);

        assertThat(log.getType()).isEqualTo(InventoryLogType.REFUND_RESTORE);
        assertThat(log.getDelta()).isEqualTo(2);
        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getBeforeHeld()).isEqualTo(3);
        assertThat(log.getBeforeSold()).isEqualTo(5);
        assertThat(log.getAfterAvailable()).isEqualTo(12);
        assertThat(log.getAfterHeld()).isEqualTo(3);
        assertThat(log.getAfterSold()).isEqualTo(3);
        assertThat(item.getAvailable()).isEqualTo(12);
        assertThat(item.getSold()).isEqualTo(3);
    }

    /**
     * happy：乐观锁版本逐笔递增（初始 0，每笔变更 +1，含多笔累计）。
     */
    @Test
    @DisplayName("乐观锁版本逐笔递增")
    void version_incrementsPerChange() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 10, 0, 0, 0);

        item.preoccupy(ORDER_ID, 3);
        assertThat(item.getVersion()).isEqualTo(1);

        item.confirmDeduct(ORDER_ID, 2);
        assertThat(item.getVersion()).isEqualTo(2);

        item.rollback(ORDER_ID, 1);
        assertThat(item.getVersion()).isEqualTo(3);
    }

    /**
     * critical：预占恰好耗尽可售（change 后 available=0 恰界——合法，
     * 三态非负守卫的边界成功路径）。
     */
    @Test
    @DisplayName("预占耗尽全部可售为合法边界")
    void preoccupy_exactExhaustionAllowed() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 0, 0, 0);

        final InventoryLog log = item.preoccupy(ORDER_ID, 5);

        assertThat(log.getAfterAvailable()).isZero();
        assertThat(item.getAvailable()).isZero();
        assertThat(item.getHeld()).isEqualTo(5);
    }

    /**
     * critical：手工调整使可售恰好归零为合法边界（调整后 available=0
     * 恰界允许）。
     */
    @Test
    @DisplayName("调整可售恰好归零为合法边界")
    void adjust_exactZeroAllowed() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 0, 0, 0);

        final InventoryLog log = item.adjust(OPERATOR, -5, null);

        assertThat(log.getAfterAvailable()).isZero();
        assertThat(item.getAvailable()).isZero();
    }

    /**
     * critical：流水形态差异——手动调整型无订单上下文（orderId 恒空），
     * 订单驱动型必带订单号；两种形态对照断言。
     */
    @Test
    @DisplayName("手动调整与订单驱动流水形态差异")
    void logContextShape_differsByType() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 10, 0, 0, 0);

        final InventoryLog adjustLog = item.adjust(OPERATOR, 2, null);
        assertThat(adjustLog.getOrderId()).isNull();

        final InventoryLog preoccupyLog = item.preoccupy(ORDER_ID, 3);
        assertThat(preoccupyLog.getOrderId()).isEqualTo(ORDER_ID);
    }

    /**
     * error：可售不足拒绝预占（超量预占 → 库存不足业务异常）。
     */
    @Test
    @DisplayName("超量预占拒绝")
    void preoccupy_overDemandRejected() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 0, 0, 0);

        assertThatThrownBy(() -> item.preoccupy(ORDER_ID, 6))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());
    }

    /**
     * error：预占数量非正拒绝（0 与负数无业务意义）。
     */
    @Test
    @DisplayName("非正预占数量拒绝")
    void preoccupy_nonPositiveDemandRejected() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 0, 0, 0);

        assertThatThrownBy(() -> item.preoccupy(ORDER_ID, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code());
        assertThatThrownBy(() -> item.preoccupy(ORDER_ID, -1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code());
    }

    /**
     * error：确认扣减超过预占量拒绝（不足预占不可确认）。
     */
    @Test
    @DisplayName("确认扣减超过预占拒绝")
    void confirmDeduct_overHeldRejected() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 0, 5, 0, 0);

        assertThatThrownBy(() -> item.confirmDeduct(ORDER_ID, 6))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());
    }

    /**
     * error：回滚数量非正拒绝。
     */
    @Test
    @DisplayName("非正回滚数量拒绝")
    void rollback_nonPositiveQuantityRejected() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 0, 5, 0, 0);

        assertThatThrownBy(() -> item.rollback(ORDER_ID, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code());
    }

    /**
     * error：调整导致可售为负拒绝（调整后 available ≥ 0 的负值守卫）。
     */
    @Test
    @DisplayName("调整致可售为负拒绝")
    void adjust_negativeResultRejected() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 0, 0, 0);

        assertThatThrownBy(() -> item.adjust(OPERATOR, -6, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());
    }

    /**
     * error：订单驱动预占缺订单号拒绝（订单驱动流水必须携带订单上下文）。
     */
    @Test
    @DisplayName("预占缺订单号拒绝")
    void preoccupy_nullOrderIdRejected() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 5, 0, 0, 0);

        assertThatThrownBy(() -> item.preoccupy(null, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * 三态负值装配拒绝（读回/创建路径的形状守卫——负库存行无
     * 业务意义）。
     */
    @Test
    @DisplayName("负可售装配拒绝")
    void construct_negativeAvailableRejected() {
        assertThatThrownBy(() -> new InventoryItem(1L, SHOP_ID, SKU_ID, -1, 0, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
    }

    /**
     * 负预占/负已售装配拒绝。
     */
    @Test
    @DisplayName("负预占或负已售装配拒绝")
    void construct_negativeHeldOrSoldRejected() {
        assertThatThrownBy(() -> new InventoryItem(1L, SHOP_ID, SKU_ID, 0, -1, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
        assertThatThrownBy(() -> new InventoryItem(1L, SHOP_ID, SKU_ID, 0, 0, -1, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
    }

    /**
     * 乐观锁版本负值装配拒绝（版本设值守卫——乐观锁只进不退）。
     */
    @Test
    @DisplayName("负乐观锁版本装配拒绝")
    void construct_negativeVersionRejected() {
        assertThatThrownBy(() -> new InventoryItem(1L, SHOP_ID, SKU_ID, 0, 0, 0, -1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
    }

    /**
     * 空归属装配拒绝（店铺/SKU 缺失的库存行无业务意义）。
     */
    @Test
    @DisplayName("空归属装配拒绝")
    void construct_missingOwnershipRejected() {
        assertThatThrownBy(() -> new InventoryItem(1L, null, SKU_ID, 0, 0, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
        assertThatThrownBy(() -> new InventoryItem(1L, SHOP_ID, null, 0, 0, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_ITEM_INVALID.code());
    }

    /**
     * 归属定型不可变——店铺/SKU 装配后无变更路径（跨店铺归属
     * 在类型上不可伪造，租户 fail-closed 的领域侧半场）。
     */
    @Test
    @DisplayName("归属装配后定型不可变")
    void construct_ownershipImmutableAfterAssembly() {
        final InventoryItem item = new InventoryItem(1L, SHOP_ID, SKU_ID, 3, 2, 1, 0);

        assertThat(item.getShopId()).isEqualTo(SHOP_ID);
        assertThat(item.getSkuId()).isEqualTo(SKU_ID);
        assertThat(item.getClass().getMethods())
                .noneMatch(m -> m.getName().equals("setShopId") || m.getName().equals("setSkuId"));
    }
}