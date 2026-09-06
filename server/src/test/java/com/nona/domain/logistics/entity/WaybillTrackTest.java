package com.nona.domain.logistics.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轨迹条目（append-only 实体）测试：形态不变量守卫（主键/归属/状态/
 * 时间必填，描述可空）与不可变性（字段 final、无变更路径）。
 * <p>
 * 红阶段：构造守卫为设计物实现（本文件用例绿）；append-only 语义由
 * 运单聚合 advanceTo 追加收敛（状态机测试红覆盖）。
 *
 * @author nona9961
 */
class WaybillTrackTest {

    /**
     * 测试轨迹主键
     */
    private static final long TRACK_ID = 8001L;

    /**
     * 测试归属运单 ID
     */
    private static final long WAYBILL_ID = 7001L;

    /**
     * 测试时间
     */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 6, 10, 0);

    // ---------- happy：整装创建 ----------

    @Test
    @DisplayName("创建轨迹：字段定型（主键/归属/状态/时间/描述）")
    void create_fullyAssembled() {
        final WaybillTrack track = new WaybillTrack(TRACK_ID, WAYBILL_ID,
                WaybillStatus.SHIPPED, T0, "已交承运发出");

        assertThat(track.getId()).isEqualTo(TRACK_ID);
        assertThat(track.getWaybillId()).isEqualTo(WAYBILL_ID);
        assertThat(track.getStatus()).isEqualTo(WaybillStatus.SHIPPED);
        assertThat(track.getOccurredAt()).isEqualTo(T0);
        assertThat(track.getDescription()).isEqualTo("已交承运发出");
    }

    @Test
    @DisplayName("创建轨迹：描述可空（非必要字段，缺失允许）")
    void create_descriptionNullable() {
        final WaybillTrack track = new WaybillTrack(TRACK_ID, WAYBILL_ID,
                WaybillStatus.IN_TRANSIT, T0, null);

        assertThat(track.getDescription()).isNull();
    }

    // ---------- critical：append-only 冻结 ----------

    @Test
    @DisplayName("轨迹不可变：字段全 final 且无修改方法（append-only 冻结）")
    void track_appendOnlyFrozen() throws Exception {
        for (Field field : WaybillTrack.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("字段 %s 必须 final（append-only）", field.getName())
                    .isTrue();
        }
        for (Method method : WaybillTrack.class.getDeclaredMethods()) {
            assertThat(method.getName().startsWith("set"))
                    .as("不允许修改方法 %s", method.getName())
                    .isFalse();
        }
    }

    // ---------- fail：形态不变量守卫 ----------

    @Test
    @DisplayName("拒绝：轨迹主键缺失")
    void create_nullId_rejected() {
        assertThatThrownBy(() -> new WaybillTrack(null, WAYBILL_ID,
                WaybillStatus.SHIPPED, T0, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：归属运单缺失")
    void create_nullWaybillId_rejected() {
        assertThatThrownBy(() -> new WaybillTrack(TRACK_ID, null,
                WaybillStatus.SHIPPED, T0, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：状态缺失")
    void create_nullStatus_rejected() {
        assertThatThrownBy(() -> new WaybillTrack(TRACK_ID, WAYBILL_ID,
                null, T0, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("拒绝：发生时间缺失")
    void create_nullOccurredAt_rejected() {
        assertThatThrownBy(() -> new WaybillTrack(TRACK_ID, WAYBILL_ID,
                WaybillStatus.SHIPPED, null, null))
                .isInstanceOf(BusinessException.class);
    }
}