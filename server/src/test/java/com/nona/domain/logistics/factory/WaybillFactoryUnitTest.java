package com.nona.domain.logistics.factory;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运单工厂单元测试：运单创建的入口（ID 生成 + 初始轨迹装配 + 形态
 * 校验）与轨迹条目装配（归属绑定 + 节点定型）。
 * <p>
 * 红阶段：工厂为设计物实现（本文件用例绿）；「一子单一在途」重复发货
 * 拒绝属发货编排守卫（编排工作单元落位），不在工厂职责——查询锚点契约钉见
 * LogisticsContractUnitTest。
 *
 * @author nona9961
 */
class WaybillFactoryUnitTest {

    /**
     * 被测工厂
     */
    private final WaybillFactory factory = new WaybillFactory();

    /**
     * 测试子单 ID
     */
    private static final long SUB_ORDER_ID = 5001L;

    /**
     * 测试时间
     */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 6, 10, 0);

    // ---------- happy：创建运单 ----------

    @Test
    @DisplayName("创建运单：生成 ID、绑定子单/公司/单号、状态待发货、自带初始轨迹")
    void createWaybill_generatesIdAndInitialTrack() {
        final Waybill waybill = factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", T0);

        assertThat(waybill.getId()).isNotNull();
        assertThat(waybill.getSubOrderId()).isEqualTo(SUB_ORDER_ID);
        assertThat(waybill.getCompany()).isEqualTo("顺丰速运");
        assertThat(waybill.getTrackingNo()).isEqualTo("SF1234567890");
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.PENDING_SHIPMENT);
        assertThat(waybill.isInTransit()).isTrue();
        assertThat(waybill.getTracks()).hasSize(1);
    }

    @Test
    @DisplayName("创建运单：初始轨迹为待发货节点（时间=创建时间，归属本运单）")
    void createWaybill_initialTrackTimeline() {
        final Waybill waybill = factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", T0);

        final WaybillTrack initial = waybill.getTracks().get(0);
        assertThat(initial.getId()).isNotNull();
        assertThat(initial.getWaybillId()).isEqualTo(waybill.getId());
        assertThat(initial.getStatus()).isEqualTo(WaybillStatus.PENDING_SHIPMENT);
        assertThat(initial.getOccurredAt()).isEqualTo(T0);
        assertThat(initial.getDescription()).isNotBlank();
    }

    // ---------- happy：创建轨迹条目 ----------

    @Test
    @DisplayName("创建轨迹：归属绑定运单主键、节点字段定型")
    void createTrack_bindsWaybill() {
        final Waybill waybill = factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", T0);

        final WaybillTrack track = factory.createTrack(waybill, WaybillStatus.SHIPPED,
                T0.plusHours(1), "已交承运发出");

        assertThat(track.getId()).isNotNull();
        assertThat(track.getWaybillId()).isEqualTo(waybill.getId());
        assertThat(track.getStatus()).isEqualTo(WaybillStatus.SHIPPED);
        assertThat(track.getOccurredAt()).isEqualTo(T0.plusHours(1));
        assertThat(track.getDescription()).isEqualTo("已交承运发出");
    }

    @Test
    @DisplayName("创建轨迹：描述可空")
    void createTrack_descriptionNullable() {
        final Waybill waybill = factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", T0);

        final WaybillTrack track = factory.createTrack(waybill, WaybillStatus.IN_TRANSIT,
                T0.plusHours(2), null);

        assertThat(track.getDescription()).isNull();
    }

    // ---------- fail：形态守卫 ----------

    @Test
    @DisplayName("拒绝：子单引用缺失")
    void createWaybill_nullSubOrderId_rejected() {
        assertThatThrownBy(() -> factory.createWaybill(null, "顺丰速运",
                "SF1234567890", T0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：承运公司空白")
    void createWaybill_blankCompany_rejected() {
        assertThatThrownBy(() -> factory.createWaybill(SUB_ORDER_ID, " ",
                "SF1234567890", T0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：运单号空白（运单号必填不变量）")
    void createWaybill_blankTrackingNo_rejected() {
        assertThatThrownBy(() -> factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "  ", T0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：创建时间缺失")
    void createWaybill_nullCreatedAt_rejected() {
        assertThatThrownBy(() -> factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：轨迹归属运单缺失")
    void createTrack_nullWaybill_rejected() {
        assertThatThrownBy(() -> factory.createTrack(null, WaybillStatus.SHIPPED,
                T0, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：轨迹状态缺失")
    void createTrack_nullStatus_rejected() {
        final Waybill waybill = factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", T0);
        assertThatThrownBy(() -> factory.createTrack(waybill, null, T0, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：轨迹发生时间缺失")
    void createTrack_nullOccurredAt_rejected() {
        final Waybill waybill = factory.createWaybill(SUB_ORDER_ID, "顺丰速运",
                "SF1234567890", T0);
        assertThatThrownBy(() -> factory.createTrack(waybill, WaybillStatus.SHIPPED,
                null, null))
                .isInstanceOf(BusinessException.class);
    }
}