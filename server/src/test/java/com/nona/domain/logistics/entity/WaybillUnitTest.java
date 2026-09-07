package com.nona.domain.logistics.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 运单聚合根测试：创建/装载装配、形态不变量守卫（子单引用/公司/单号/
 * 轨迹必填、末条轨迹与当前状态一致）、在途判定边界、轨迹不可变视图。
 * <p>
 * 红阶段：构造守卫与查询判定（isInTransit）为设计物实现（本文件用例
 * 绿）；状态迁移（advanceTo）实现缺失由状态机测试红覆盖。
 *
 * @author nona9961
 */
class WaybillUnitTest {

    /**
     * 测试运单主键
     */
    private static final long WAYBILL_ID = 7001L;

    /**
     * 测试子单 ID
     */
    private static final long SUB_ORDER_ID = 5001L;

    /**
     * 测试时间
     */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 6, 10, 0);

    /**
     * 构造指定状态的轨迹条目（归属本运单）。
     *
     * @param status 轨迹状态
     * @return 轨迹
     */
    private static WaybillTrack track(WaybillStatus status) {
        return new WaybillTrack(WAYBILL_ID + status.ordinal(), WAYBILL_ID,
                status, T0.plusHours(status.ordinal()), "节点" + status.name());
    }

    /**
     * 构造指定状态的运单（装载构造器直接装配：末条轨迹状态与当前状态一致）。
     *
     * @param status  目标状态
     * @param history 前置轨迹（可选；恒定以目标状态轨迹收尾与状态一致）
     * @return 运单
     */
    private static Waybill waybillOf(WaybillStatus status, WaybillTrack... history) {
        return new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运", "SF1234567890",
                status, java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(history), java.util.stream.Stream.of(track(status)))
                .toList());
    }

    // ---------- happy：创建/装载/在途 ----------

    @Test
    @DisplayName("创建运单：字段定型（子单/公司/单号/待发货状态/初始轨迹）")
    void create_fullyAssembled() {
        final Waybill waybill = waybillOf(WaybillStatus.PENDING_SHIPMENT);

        assertThat(waybill.getId()).isEqualTo(WAYBILL_ID);
        assertThat(waybill.getSubOrderId()).isEqualTo(SUB_ORDER_ID);
        assertThat(waybill.getCompany()).isEqualTo("顺丰速运");
        assertThat(waybill.getTrackingNo()).isEqualTo("SF1234567890");
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.PENDING_SHIPMENT);
        assertThat(waybill.getTracks()).hasSize(1);
        assertThat(waybill.getTracks().get(0).getStatus()).isEqualTo(WaybillStatus.PENDING_SHIPMENT);
        assertThat(waybill.isInTransit()).isTrue();
    }

    @Test
    @DisplayName("装载恢复：状态与轨迹时间线从持久化值恢复")
    void load_restoresStatusAndTracks() {
        final Waybill waybill = new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", WaybillStatus.SHIPPED,
                List.of(track(WaybillStatus.PENDING_SHIPMENT), track(WaybillStatus.SHIPPED)));

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPPED);
        assertThat(waybill.getTracks()).hasSize(2);
        assertThat(waybill.getTracks().get(1).getStatus()).isEqualTo(WaybillStatus.SHIPPED);
    }

    @Test
    @DisplayName("在途判定边界：待发货/已发货/运输中在途，已签收不在途")
    void isInTransit_boundary() {
        assertThat(waybillOf(WaybillStatus.PENDING_SHIPMENT).isInTransit()).isTrue();
        assertThat(waybillOf(WaybillStatus.SHIPPED).isInTransit()).isTrue();
        assertThat(waybillOf(WaybillStatus.IN_TRANSIT).isInTransit()).isTrue();
        assertThat(waybillOf(WaybillStatus.DELIVERED).isInTransit()).isFalse();
    }

    // ---------- critical：轨迹不可变视图 ----------

    @Test
    @DisplayName("轨迹列表不可变：外部读面修改即抛异常，字段不可再变")
    void tracks_snapshotFrozen() throws Exception {
        final Waybill waybill = waybillOf(WaybillStatus.PENDING_SHIPMENT);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> waybill.getTracks().add(track(WaybillStatus.SHIPPED)));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> waybill.getTracks().clear());
        for (Field field : Waybill.class.getDeclaredFields()) {
            if (!field.getName().equals("status")) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("字段 %s 必须 final（除 status 外全冻结）", field.getName())
                        .isTrue();
            }
        }
    }

    // ---------- fail：形态不变量守卫 ----------

    @Test
    @DisplayName("拒绝：承运公司空白")
    void create_blankCompany_rejected() {
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, SUB_ORDER_ID, "  ",
                "SF1234567890", WaybillStatus.PENDING_SHIPMENT,
                List.of(track(WaybillStatus.PENDING_SHIPMENT))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：运单号空白")
    void create_blankTrackingNo_rejected() {
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运",
                " ", WaybillStatus.PENDING_SHIPMENT,
                List.of(track(WaybillStatus.PENDING_SHIPMENT))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：子单引用缺失")
    void create_nullSubOrderId_rejected() {
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, null, "顺丰速运",
                "SF1234567890", WaybillStatus.PENDING_SHIPMENT,
                List.of(track(WaybillStatus.PENDING_SHIPMENT))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：轨迹集合为空（时间线至少一个节点）")
    void create_emptyTracks_rejected() {
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", WaybillStatus.PENDING_SHIPMENT, List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：状态缺失")
    void create_nullStatus_rejected() {
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", null, List.of(track(WaybillStatus.PENDING_SHIPMENT))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：装载轨迹末条状态与运单当前状态不一致（时间线自相矛盾）")
    void load_trackStatusMismatch_rejected() {
        final WaybillTrack pending = track(WaybillStatus.PENDING_SHIPMENT);
        final WaybillTrack inTransit = track(WaybillStatus.IN_TRANSIT);
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", WaybillStatus.SHIPPED, List.of(pending, inTransit)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：轨迹归属其他运单（装配错误）")
    void load_trackBelongsToOtherWaybill_rejected() {
        final WaybillTrack foreign = new WaybillTrack(9001L, 9999L,
                WaybillStatus.PENDING_SHIPMENT, T0, "他单轨迹");
        assertThatThrownBy(() -> new Waybill(WAYBILL_ID, SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", WaybillStatus.PENDING_SHIPMENT, List.of(foreign)))
                .isInstanceOf(BusinessException.class);
    }
}