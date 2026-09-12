package com.nona.domain.logistics;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.factory.WaybillFactory;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.persistence.BaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 物流域契约钉（契约即绿）：状态值定型集合、聚合/工厂/仓储签名与
 * 业务码（只增不改）防漂移——实现期签名、码值、枚举集合改动即失败，
 * 消费编排（发货 / 模拟推进 / 平台视图）按本契约
 * 装配。
 *
 * @author nona9961
 */
class LogisticsContractUnitTest {

    // ---------- 状态值定型 ----------

    @Test
    @DisplayName("运单状态值定型：4 值精确集合（待发货/已发货/运输中/已签收）")
    void waybillStatus_valuesFrozen() {
        assertThat(WaybillStatus.values()).containsExactlyInAnyOrder(
                WaybillStatus.PENDING_SHIPMENT, WaybillStatus.SHIPPED,
                WaybillStatus.IN_TRANSIT, WaybillStatus.DELIVERED);
        assertThat(WaybillStatus.values()).hasSize(4);
    }

    @Test
    @DisplayName("状态解析：运单状态名解析可用，未知值拒绝")
    void waybillStatus_fromName() {
        assertThat(WaybillStatus.fromName("PENDING_SHIPMENT")).isEqualTo(WaybillStatus.PENDING_SHIPMENT);
        assertThat(WaybillStatus.fromName("SHIPPED")).isEqualTo(WaybillStatus.SHIPPED);
        assertThat(WaybillStatus.fromName("IN_TRANSIT")).isEqualTo(WaybillStatus.IN_TRANSIT);
        assertThat(WaybillStatus.fromName("DELIVERED")).isEqualTo(WaybillStatus.DELIVERED);
        assertThatThrownBy(() -> WaybillStatus.fromName("UNKNOWN"))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- 聚合签名 ----------

    @Test
    @DisplayName("运单聚合：推进与在途判定签名定型")
    void waybill_advanceToSignatureFrozen() throws Exception {
        assertThat(Waybill.class.getMethod("advanceTo", WaybillTrack.class)
                .getReturnType()).isEqualTo(void.class);
        assertThat(Waybill.class.getMethod("isInTransit").getReturnType())
                .isEqualTo(boolean.class);
        assertThat(Waybill.class.getMethod("getTracks").getReturnType().getSimpleName())
                .isEqualTo("List");
        assertThat(Waybill.class.getMethod("getId").getReturnType()).isEqualTo(Long.class);
        assertThat(Waybill.class.getMethod("getSubOrderId").getReturnType()).isEqualTo(Long.class);
        assertThat(Waybill.class.getMethod("getCompany").getReturnType()).isEqualTo(String.class);
        assertThat(Waybill.class.getMethod("getTrackingNo").getReturnType()).isEqualTo(String.class);
        assertThat(Waybill.class.getMethod("getStatus").getReturnType()).isEqualTo(WaybillStatus.class);
    }

    @Test
    @DisplayName("轨迹实体：构造签名与读取面定型（append-only）")
    void waybillTrack_constructorFrozen() throws Exception {
        final var ctor = WaybillTrack.class.getConstructor(
                Long.class, Long.class, WaybillStatus.class, LocalDateTime.class, String.class);
        assertThat(ctor.getParameterCount()).isEqualTo(5);
        assertThat(WaybillTrack.class.getMethod("getId").getReturnType()).isEqualTo(Long.class);
        assertThat(WaybillTrack.class.getMethod("getWaybillId").getReturnType()).isEqualTo(Long.class);
        assertThat(WaybillTrack.class.getMethod("getStatus").getReturnType()).isEqualTo(WaybillStatus.class);
        assertThat(WaybillTrack.class.getMethod("getOccurredAt").getReturnType()).isEqualTo(LocalDateTime.class);
        assertThat(WaybillTrack.class.getMethod("getDescription").getReturnType()).isEqualTo(String.class);
        for (Method method : WaybillTrack.class.getDeclaredMethods()) {
            assertThat(method.getName().startsWith("set")).isFalse();
        }
    }

    // ---------- 仓储契约 ----------

    @Test
    @DisplayName("运单仓储：继承 BaseRepository，含一子单一在途查询锚点")
    void waybillRepository_contractFrozen() throws Exception {
        assertThat(BaseRepository.class.isAssignableFrom(WaybillRepository.class)).isTrue();
        final Method findInTransit = WaybillRepository.class.getMethod(
                "findInTransitBySubOrderId", Long.class);
        assertThat(findInTransit.getReturnType()).isEqualTo(Optional.class);
        assertThat(Modifier.isInterface(WaybillRepository.class.getModifiers())).isTrue();
    }

    // ---------- 工厂签名 ----------

    @Test
    @DisplayName("工厂签名：创建运单/创建轨迹定型")
    void factory_signaturesFrozen() throws Exception {
        assertThat(WaybillFactory.class.getMethod("createWaybill",
                Long.class, String.class, String.class, LocalDateTime.class)
                .getReturnType()).isEqualTo(Waybill.class);
        assertThat(WaybillFactory.class.getMethod("createTrack",
                Waybill.class, WaybillStatus.class, LocalDateTime.class, String.class)
                .getReturnType()).isEqualTo(WaybillTrack.class);
    }

    // ---------- 业务码 ----------

    @Test
    @DisplayName("物流业务码值定型（只增不改）")
    void businessCodes_frozen() {
        assertThat(EcommerceBusinessCode.LOGISTICS_NOT_FOUND.code()).isEqualTo("logistics.not_found");
        assertThat(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code()).isEqualTo("logistics.waybill_invalid");
        assertThat(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code()).isEqualTo("logistics.track_invalid");
        assertThat(EcommerceBusinessCode.LOGISTICS_COMPANY_BLANK.code()).isEqualTo("logistics.company_blank");
        assertThat(EcommerceBusinessCode.LOGISTICS_TRACKING_NO_BLANK.code()).isEqualTo("logistics.tracking_no_blank");
        assertThat(EcommerceBusinessCode.LOGISTICS_STATUS_ILLEGAL.code()).isEqualTo("logistics.status_illegal");
        assertThat(EcommerceBusinessCode.LOGISTICS_SUB_ORDER_CONFLICT.code()).isEqualTo("logistics.sub_order_conflict");
    }

    @Test
    @DisplayName("物流业务码默认状态映射（404/400/400/400/400/400/409）")
    void businessCodes_statusMapping() {
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.not_found")).isEqualTo(404);
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.waybill_invalid")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.track_invalid")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.company_blank")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.tracking_no_blank")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.status_illegal")).isEqualTo(400);
        assertThat(EcommerceBusinessCode.defaultStatus("logistics.sub_order_conflict")).isEqualTo(409);
    }
}