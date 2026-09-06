package com.nona.domain.logistics.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运单状态机测试：迁移守卫收敛在聚合方法 advanceTo——happy（单步推进/
 * 全链推进/轨迹携带时间描述）、critical（轨迹追加后不可变视图）、fail
 * （跳级/重复/回退/终态推进/轨迹归属他单/空轨迹拒绝）。
 * <p>
 * 红阶段：advanceTo 实现体为 UnsupportedOperationException——happy
 * 用例因 UOE 红（Error），fail 用例「期望 BusinessException 实得 UOE」
 * 红（Failure），红因均为实现缺失；测试语义按最终迁移契约书写（绿阶段
 * 实现后无需改写）。状态前置经装载构造器直接装配（装载路径校验形态
 * 不变量，不做写路径校验）。
 *
 * @author nona9961
 */
class WaybillStatusMachineTest {

    /**
     * 测试运单主键
     */
    private static final long WAYBILL_ID = 7001L;

    /**
     * 测试他单主键（归属校验用）
     */
    private static final long OTHER_WAYBILL_ID = 7999L;

    /**
     * 测试子单 ID
     */
    private static final long SUB_ORDER_ID = 5001L;

    /**
     * 测试时间
     */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 6, 10, 0);

    /**
     * 构造归属本运单的目标状态轨迹（时间随状态推进 +1 小时，描述带文案）。
     *
     * @param status 目标状态
     * @return 轨迹
     */
    private static WaybillTrack track(WaybillStatus status) {
        return new WaybillTrack(WAYBILL_ID + status.ordinal(), WAYBILL_ID,
                status, T0.plusHours(status.ordinal()), "节点：" + status.name());
    }

    /**
     * 构造指定状态的运单（装载构造器装配：末条轨迹状态与当前状态一致）。
     *
     * @param status 目标状态
     * @return 运单
     */
    private static Waybill waybill(WaybillStatus status) {
        return new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运", "SF1234567890",
                status, List.of(track(status)));
    }

    // ---------- happy：正常推进 ----------

    @Test
    @DisplayName("推进：待发货 → 已发货（状态前移，轨迹追加，仍为在途）")
    void advance_pendingToShipped() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        final WaybillTrack shipped = track(WaybillStatus.SHIPPED);

        waybill.advanceTo(shipped);

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPPED);
        assertThat(waybill.getTracks()).hasSize(2);
        assertThat(waybill.getTracks().get(1)).isSameAs(shipped);
        assertThat(waybill.isInTransit()).isTrue();
    }

    @Test
    @DisplayName("推进：全链待发货 → 已发货 → 运输中 → 已签收（终态闭合）")
    void advance_fullChain() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);

        waybill.advanceTo(track(WaybillStatus.SHIPPED));
        waybill.advanceTo(track(WaybillStatus.IN_TRANSIT));
        waybill.advanceTo(track(WaybillStatus.DELIVERED));

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.DELIVERED);
        assertThat(waybill.getTracks()).hasSize(4);
        assertThat(waybill.isInTransit()).isFalse();
    }

    @Test
    @DisplayName("推进：轨迹携带发生时间与描述（时间线节点语义）")
    void advance_carriesTimeAndDescription() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        final WaybillTrack inTransit = new WaybillTrack(8801L, WAYBILL_ID,
                WaybillStatus.IN_TRANSIT, T0.plusHours(5), "已到达华中转运中心");

        waybill.advanceTo(track(WaybillStatus.SHIPPED));
        waybill.advanceTo(inTransit);

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.IN_TRANSIT);
        final WaybillTrack appended = waybill.getTracks().get(2);
        assertThat(appended.getOccurredAt()).isEqualTo(T0.plusHours(5));
        assertThat(appended.getDescription()).isEqualTo("已到达华中转运中心");
    }

    // ---------- critical：追加后的不可变视图 ----------

    @Test
    @DisplayName("推进后轨迹列表仍为不可变视图（外部修改抛异常）")
    void tracks_unmodifiableView() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        waybill.advanceTo(track(WaybillStatus.SHIPPED));

        assertThat(waybill.getTracks()).hasSize(2);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> waybill.getTracks().add(track(WaybillStatus.IN_TRANSIT)));
    }

    // ---------- fail：非法迁移/装配守卫 ----------

    @Test
    @DisplayName("拒绝：跳一级推进（待发货 → 运输中）")
    void advance_skipLevel_rejected() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        assertThatThrownBy(() -> waybill.advanceTo(track(WaybillStatus.IN_TRANSIT)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：跳两级推进（待发货 → 已签收）")
    void advance_skipTwoLevels_rejected() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        assertThatThrownBy(() -> waybill.advanceTo(track(WaybillStatus.DELIVERED)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：重复推进（已发货再推进为已发货）")
    void advance_duplicate_rejected() {
        final Waybill waybill = waybill(WaybillStatus.SHIPPED);
        assertThatThrownBy(() -> waybill.advanceTo(track(WaybillStatus.SHIPPED)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：回退推进（运输中退回已发货）")
    void advance_rollback_rejected() {
        final Waybill waybill = waybill(WaybillStatus.IN_TRANSIT);
        assertThatThrownBy(() -> waybill.advanceTo(track(WaybillStatus.SHIPPED)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：终态再推进（已签收已无出边）")
    void advance_terminal_rejected() {
        final Waybill waybill = waybill(WaybillStatus.DELIVERED);
        assertThatThrownBy(() -> waybill.advanceTo(track(WaybillStatus.DELIVERED)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：空轨迹")
    void advance_nullTrack_rejected() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        assertThatThrownBy(() -> waybill.advanceTo(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：轨迹归属其他运单（装配错误防串单）")
    void advance_trackBelongsToOtherWaybill_rejected() {
        final Waybill waybill = waybill(WaybillStatus.PENDING_SHIPMENT);
        final WaybillTrack foreign = new WaybillTrack(9901L, OTHER_WAYBILL_ID,
                WaybillStatus.SHIPPED, T0.plusHours(1), "他单轨迹");
        assertThatThrownBy(() -> waybill.advanceTo(foreign))
                .isInstanceOf(BusinessException.class);
    }
}