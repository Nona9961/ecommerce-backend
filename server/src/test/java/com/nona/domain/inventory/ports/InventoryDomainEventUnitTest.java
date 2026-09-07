package com.nona.domain.inventory.ports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存领域事件类型契约测试（售罄/恢复事件——跨上下文契约的发布侧
 * 类型定型）：载荷形态（仅 skuId 最小定位信息）、事件类型标识与时间戳
 * 语义（判定通过、事件构造时点）。
 *
 * @author nona9961
 */
class InventoryDomainEventUnitTest {

    /**
     * 售罄事件契约：类型标识、载荷 SKU、时间戳构造时点就位。
     */
    @Test
    @DisplayName("售罄事件暴露类型标识与最小载荷")
    void selloutEvent_exposesContract() {
        final SelloutEvent event = new SelloutEvent(88001L);

        assertThat(event.getType()).isEqualTo(SelloutEvent.TYPE);
        assertThat(SelloutEvent.TYPE).isEqualTo("Sellout");
        assertThat(event.getPayload().skuId()).isEqualTo(88001L);
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.timestamp()).isBeforeOrEqualTo(java.time.Instant.now());
    }

    /**
     * 可售恢复事件契约：类型标识、载荷 SKU、时间戳构造时点就位。
     */
    @Test
    @DisplayName("恢复事件暴露类型标识与最小载荷")
    void restockEvent_exposesContract() {
        final RestockEvent event = new RestockEvent(88002L);

        assertThat(event.getType()).isEqualTo(RestockEvent.TYPE);
        assertThat(RestockEvent.TYPE).isEqualTo("Restock");
        assertThat(event.getPayload().skuId()).isEqualTo(88002L);
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.timestamp()).isBeforeOrEqualTo(java.time.Instant.now());
    }
}