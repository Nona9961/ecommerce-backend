package com.nona.domain.logistics.ports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物流签收领域事件类型契约测试（跨上下文契约的发布侧类型定型）：
 * 载荷形态（waybillId + subOrderId 最小定位信息）、事件类型标识与
 * 时间戳语义（签收推进通过、事件构造时点）。
 *
 * @author nona9961
 */
class WaybillDeliveredUnitTest {

    /**
     * 签收事件契约：类型标识、载荷定位引用、时间戳构造时点就位。
     */
    @Test
    @DisplayName("签收事件暴露类型标识与最小载荷")
    void waybillDelivered_exposesContract() {
        final WaybillDelivered event = new WaybillDelivered(9001L, 101L);

        assertThat(event.getType()).isEqualTo(WaybillDelivered.TYPE);
        assertThat(WaybillDelivered.TYPE).isEqualTo("WaybillDelivered");
        assertThat(event.getPayload().waybillId()).isEqualTo(9001L);
        assertThat(event.getPayload().subOrderId()).isEqualTo(101L);
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.timestamp()).isBeforeOrEqualTo(java.time.Instant.now());
    }

    /**
     * 事件基座契约：载荷/类型/时间戳三相访问器直返（数据契约，无分支）。
     */
    @Test
    @DisplayName("签收事件实现事件基座访问器")
    void waybillDelivered_implementsEventContract() {
        final WaybillDelivered event = new WaybillDelivered(9002L, 102L);

        assertThat(event).isInstanceOf(com.nona.events.Event.class);
        assertThat(event.getPayload()).isEqualTo(new WaybillDelivered.WaybillDeliveredData(9002L, 102L));
        assertThat(event.getType()).isEqualTo("WaybillDelivered");
    }

    /**
     * 载荷定型：签收事件每次构造各自独立（无共享可变状态——多运单
     * 签收互不串扰）。
     */
    @Test
    @DisplayName("签收事件载荷逐事件独立")
    void waybillDelivered_payloadIsolated() {
        final WaybillDelivered first = new WaybillDelivered(9003L, 103L);
        final WaybillDelivered second = new WaybillDelivered(9004L, 104L);

        assertThat(first.getPayload().waybillId()).isEqualTo(9003L);
        assertThat(first.getPayload().subOrderId()).isEqualTo(103L);
        assertThat(second.getPayload().waybillId()).isEqualTo(9004L);
        assertThat(second.getPayload().subOrderId()).isEqualTo(104L);
    }
}