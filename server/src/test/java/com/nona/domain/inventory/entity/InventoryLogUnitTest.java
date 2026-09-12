package com.nona.domain.inventory.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存流水行单元测试：append-only 不可变构造契约（「每笔变更必有流水、
 * 零变更不可能、方向由类型界定、前后快照算术自洽、上下文形态与类型
 * 一致」的不变量全部收敛在构造路径）。
 * <p>
 * 覆盖：happy 各型构造与访问器 / critical 恰界数量与手动调整负向 /
 * error 零变更、负快照、算术不一致、上下文形态不符各拒 / 幂等键语义。
 *
 * @author nona9961
 */
class InventoryLogUnitTest {

    /**
     * 归属店铺（构造基线）
     */
    private static final long SHOP_ID = 1001L;

    /**
     * 归属 SKU（构造基线）
     */
    private static final long SKU_ID = 88001L;

    /**
     * 目标订单（订单驱动基线）
     */
    private static final long ORDER_ID = 660001L;

    /**
     * 操作人（手动调整基线）
     */
    private static final String OPERATOR = "nona9961";

    /**
     * happy：PREOCCUPY 行全字段构造——方向由类型界定（delta 恒正），
     * 订单上下文必填，before/after 三态快照就位。
     */
    @Test
    @DisplayName("PREOCCUPY 流水构造与访问器")
    void preoccupyLog_constructsWithFullShape() {
        final InventoryLog log = new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                10, 0, 0, 7, 3, 0, null, null);

        assertThat(log.getId()).isEqualTo(1L);
        assertThat(log.getShopId()).isEqualTo(SHOP_ID);
        assertThat(log.getSkuId()).isEqualTo(SKU_ID);
        assertThat(log.getType()).isEqualTo(InventoryLogType.PREOCCUPY);
        assertThat(log.getDelta()).isEqualTo(3);
        assertThat(log.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getBeforeHeld()).isZero();
        assertThat(log.getBeforeSold()).isZero();
        assertThat(log.getAfterAvailable()).isEqualTo(7);
        assertThat(log.getAfterHeld()).isEqualTo(3);
        assertThat(log.getAfterSold()).isZero();
        assertThat(log.getOperator()).isNull();
        assertThat(log.getReason()).isNull();
        assertThat(log.getCreatedAt()).isNull();
    }

    /**
     * happy：各型流水构造成立（CONFIRM 预占→已售 / ROLLBACK 预占→可售
     * / MANUAL_ADJUST 仅可售 / REFUND_RESTORE 已售→可售）。
     */
    @Test
    @DisplayName("各类型流水构造成立")
    void allTypes_constructConsistently() {
        assertThat(new InventoryLog(1L, SHOP_ID, SKU_ID, InventoryLogType.CONFIRM, 2, ORDER_ID,
                5, 5, 0, 5, 3, 2, null, null).getType())
                .isEqualTo(InventoryLogType.CONFIRM);
        assertThat(new InventoryLog(2L, SHOP_ID, SKU_ID, InventoryLogType.ROLLBACK, 2, ORDER_ID,
                5, 5, 0, 7, 3, 0, null, null).getType())
                .isEqualTo(InventoryLogType.ROLLBACK);
        assertThat(new InventoryLog(3L, SHOP_ID, SKU_ID, InventoryLogType.MANUAL_ADJUST, 4, null,
                10, 0, 0, 14, 0, 0, OPERATOR, "补货入库").getType())
                .isEqualTo(InventoryLogType.MANUAL_ADJUST);
        assertThat(new InventoryLog(4L, SHOP_ID, SKU_ID, InventoryLogType.REFUND_RESTORE, 2, ORDER_ID,
                10, 3, 5, 12, 3, 3, null, null).getType())
                .isEqualTo(InventoryLogType.REFUND_RESTORE);
    }

    /**
     * happy：读回形态——createdAt 随持久化读回就位。
     */
    @Test
    @DisplayName("读回形态携带产生时间")
    void readBackShape_carriesCreatedAt() {
        final LocalDateTime createdAt = LocalDateTime.of(2026, 9, 4, 10, 0, 0);
        final InventoryLog log = new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                10, 0, 0, 7, 3, 0, null, null, createdAt);

        assertThat(log.getCreatedAt()).isEqualTo(createdAt);
    }

    /**
     * critical：数量恰界——delta=1 为合法最小正数量（边界成功路径）。
     */
    @Test
    @DisplayName("数量 1 为合法最小变更")
    void deltaOne_isValidBoundary() {
        final InventoryLog log = new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 1, ORDER_ID,
                1, 0, 0, 0, 1, 0, null, null);

        assertThat(log.getDelta()).isEqualTo(1);
    }

    /**
     * critical：手动调整型负 delta 合法（减可售方向由符号表达，与订单
     * 驱动型「方向由类型界定、delta 恒正」形态区分）。
     */
    @Test
    @DisplayName("手动调整负向减可售合法")
    void manualAdjust_negativeDeltaAllowed() {
        final InventoryLog log = new InventoryLog(
                3L, SHOP_ID, SKU_ID, InventoryLogType.MANUAL_ADJUST, -4, null,
                10, 0, 0, 6, 0, 0, OPERATOR, null);

        assertThat(log.getDelta()).isEqualTo(-4);
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getAfterAvailable()).isEqualTo(6);
    }

    /**
     * error：零变更不产流水（delta=0 拒绝——「零流水变更不可能」不变量
     * 的构造侧守卫）。
     */
    @Test
    @DisplayName("零变更流水拒绝")
    void zeroDelta_rejected() {
        assertThatThrownBy(() -> new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 0, ORDER_ID,
                10, 0, 0, 10, 0, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("零变更不产生流水")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * error：订单驱动型负 delta 拒绝（方向由类型界定，数量本身恒正）。
     */
    @Test
    @DisplayName("订单驱动型负数量拒绝")
    void orderDriven_negativeDeltaRejected() {
        assertThatThrownBy(() -> new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, -3, ORDER_ID,
                10, 0, 0, 13, -3, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
        assertThatThrownBy(() -> new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.CONFIRM, -2, ORDER_ID,
                5, 5, 0, 5, 7, -2, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * error：手动调整型携带订单号拒绝（手动调整无订单上下文）。
     */
    @Test
    @DisplayName("手动调整携订单号拒绝")
    void manualAdjust_withOrderIdRejected() {
        assertThatThrownBy(() -> new InventoryLog(
                3L, SHOP_ID, SKU_ID, InventoryLogType.MANUAL_ADJUST, 4, ORDER_ID,
                10, 0, 0, 14, 0, 0, OPERATOR, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不携带订单")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * error：订单驱动型缺订单号拒绝（订单驱动流水必须携带订单上下文，
     * 幂等键 (order_id, sku_id, type) 的领域侧守卫）。
     */
    @Test
    @DisplayName("订单驱动型缺订单号拒绝")
    void orderDriven_missingOrderIdRejected() {
        assertThatThrownBy(() -> new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, null,
                10, 0, 0, 7, 3, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须携带订单")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
        assertThatThrownBy(() -> new InventoryLog(
                2L, SHOP_ID, SKU_ID, InventoryLogType.REFUND_RESTORE, 2, null,
                10, 3, 5, 12, 3, 3, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须携带订单")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * error：手动调整型缺操作人拒绝（调整语义确认：操作人必填）。
     */
    @Test
    @DisplayName("手动调整缺操作人拒绝")
    void manualAdjust_missingOperatorRejected() {
        assertThatThrownBy(() -> new InventoryLog(
                3L, SHOP_ID, SKU_ID, InventoryLogType.MANUAL_ADJUST, 4, null,
                10, 0, 0, 14, 0, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("操作人")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * error：前后快照负值拒绝（三态恒非负）。
     */
    @Test
    @DisplayName("负快照拒绝")
    void negativeSnapshotRejected() {
        assertThatThrownBy(() -> new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                -1, 0, 0, 2, -1, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须非负")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * error：前后快照与 (类型, 数量) 算术不一致拒绝——自相矛盾的流水行
     * 无审计意义（逐型反例）。
     */
    @Test
    @DisplayName("算术不一致快照拒绝")
    void arithmeticInconsistentSnapshotRejected() {
        assertThatThrownBy(() -> new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                10, 0, 0, 8, 3, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("算术不一致")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
        assertThatThrownBy(() -> new InventoryLog(
                3L, SHOP_ID, SKU_ID, InventoryLogType.MANUAL_ADJUST, 4, null,
                10, 0, 0, 10, 4, 0, OPERATOR, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("算术不一致")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
        assertThatThrownBy(() -> new InventoryLog(
                4L, SHOP_ID, SKU_ID, InventoryLogType.REFUND_RESTORE, 2, ORDER_ID,
                10, 3, 5, 14, 3, 3, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("算术不一致")
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());
    }

    /**
     * 幂等键：同 (orderId, skuId, type) 判定重复——重复变更由唯一约束
     * 拒绝的领域侧识别语义。
     */
    @Test
    @DisplayName("幂等键相同判定")
    void idempotencyKey_sameKeyRecognized() {
        final InventoryLog first = new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                10, 0, 0, 7, 3, 0, null, null);
        final InventoryLog duplicate = new InventoryLog(
                2L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                7, 3, 0, 4, 6, 0, null, null);

        assertThat(first.sameIdempotencyKey(duplicate)).isTrue();
    }

    /**
     * 幂等键：订单/类型任一不同的流水行互不判定重复。
     */
    @Test
    @DisplayName("幂等键差异判定")
    void idempotencyKey_differentKeyDistinguished() {
        final InventoryLog base = new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                10, 0, 0, 7, 3, 0, null, null);
        final InventoryLog otherOrder = new InventoryLog(
                2L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID + 1,
                10, 0, 0, 7, 3, 0, null, null);
        final InventoryLog otherType = new InventoryLog(
                3L, SHOP_ID, SKU_ID, InventoryLogType.CONFIRM, 3, ORDER_ID,
                7, 3, 0, 7, 0, 3, null, null);

        assertThat(base.sameIdempotencyKey(otherOrder)).isFalse();
        assertThat(base.sameIdempotencyKey(otherType)).isFalse();
        assertThat(base.sameIdempotencyKey(base)).isTrue();
    }

    /**
     * 不可变形态：流水行无任何 setter（append-only——行内容构造时定型，
     * 无改写路径的编译级保证）。
     */
    @Test
    @DisplayName("流水行无 setter 不可变")
    void log_hasNoSetters() {
        final InventoryLog log = new InventoryLog(
                1L, SHOP_ID, SKU_ID, InventoryLogType.PREOCCUPY, 3, ORDER_ID,
                10, 0, 0, 7, 3, 0, null, null);

        assertThat(log.getClass().getMethods())
                .noneMatch(m -> m.getName().startsWith("set"));
        assertThat(log.getDelta()).isEqualTo(3);
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getAfterAvailable()).isEqualTo(7);
    }
}