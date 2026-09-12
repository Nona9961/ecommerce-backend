package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.ports.InventoryEventPublisher;
import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 售罄/恢复事件统一触发点单元测试：判定语义（基于流水行 before/after
 * 三态快照）——变更后 available=0 且 held=0 发布售罄；变更前已售罄且
 * 变更后未售罄发布恢复；其余变更不发布；每次变更至多发布一个事件。
 * <ul>
 *     <li>happy：调整致售罄态 → 售罄事件（载荷 SKU 就位）；扣减耗尽
 *         致售罄态 → 售罄事件；</li>
 *     <li>critical：可售归零但 held>0 不触发（售罄定义的恰界）；售罄
 *         后调整恢复 → 恢复事件（发布恰好一次）；</li>
 *     <li>error：普通变更（未售罄、非恢复）不发布；售罄变更不发布
 *         恢复事件（互斥，无重复发布）。</li>
 * </ul>
 * 本触发点判定实现后全绿（判定语义与发布动作经 mock 端口验证）。
 *
 * @author nona9961
 */
class InventoryEventRouterUnitTest {

    /**
     * 被测发布端口（mock）
     */
    private final InventoryEventPublisher publisher = mock(InventoryEventPublisher.class);

    /**
     * 被测统一触发点（与发布端口同实例组装）
     */
    private final InventoryEventRouter router = new InventoryEventRouter(publisher);

    /**
     * 每用例前：重置 mock 交互记录（防跨用例污染）。
     */
    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(publisher);
    }

    /**
     * happy：调整致售罄态（available=0 且 held=0）→ 售罄事件发布一次，
     * 载荷携带 SKU；不发布恢复事件。
     */
    @Test
    @DisplayName("调整致售罄态发布售罄事件且载荷就位")
    void sellout_afterAdjustToZero_publishesSellout() {
        final InventoryItem item = item(3, 0, 0);
        final InventoryLog log = item.adjust("operator-1", -3, null);

        router.publishIfNeeded(log);

        final ArgumentCaptor<SelloutEvent> sellout = ArgumentCaptor.forClass(SelloutEvent.class);
        verify(publisher, times(1)).publishSellout(sellout.capture());
        assertThat(sellout.getValue().getPayload().skuId()).isEqualTo(88001L);
        assertThat(sellout.getValue().getType()).isEqualTo(SelloutEvent.TYPE);
        verify(publisher, never()).publishRestock(org.mockito.ArgumentMatchers.any());
    }

    /**
     * happy：扣减耗尽致售罄态（可售已零、预占清零）→ 售罄事件发布。
     */
    @Test
    @DisplayName("扣减耗尽预占清零发布售罄事件")
    void sellout_afterDeductToZero_publishesSellout() {
        final InventoryItem item = item(3, 0, 0);
        item.preoccupy(900L, 3);

        final InventoryLog log = item.confirmDeduct(900L, 3);

        router.publishIfNeeded(log);

        final ArgumentCaptor<SelloutEvent> sellout = ArgumentCaptor.forClass(SelloutEvent.class);
        verify(publisher, times(1)).publishSellout(sellout.capture());
        assertThat(sellout.getValue().getPayload().skuId()).isEqualTo(88001L);
    }

    /**
     * critical：可售归零但 held>0（在途预占未清）——非售罄态，不发布
     * 任何事件。
     */
    @Test
    @DisplayName("可售归零但held大于零不触发售罄")
    void sellout_heldPositive_noEvent() {
        final InventoryItem item = item(2, 3, 0);
        final InventoryLog log = item.adjust("operator-1", -2, null);

        router.publishIfNeeded(log);

        verifyNoInteractions(publisher);
    }

    /**
     * critical：此前已售罄、调整恢复可售（available>0）→ 恢复事件发布
     * 恰好一次（「此前已售罄」由 before 快照判定，无状态追踪）。
     */
    @Test
    @DisplayName("售罄后调整恢复可售发布恢复事件")
    void restock_fromSelloutPublishesRestock() {
        final InventoryItem item = item(0, 0, 0);
        final InventoryLog log = item.adjust("operator-1", 5, "补货上架");

        router.publishIfNeeded(log);

        final ArgumentCaptor<RestockEvent> restock = ArgumentCaptor.forClass(RestockEvent.class);
        verify(publisher, times(1)).publishRestock(restock.capture());
        assertThat(restock.getValue().getPayload().skuId()).isEqualTo(88001L);
        assertThat(restock.getValue().getType()).isEqualTo(RestockEvent.TYPE);
        verify(publisher, never()).publishSellout(org.mockito.ArgumentMatchers.any());
    }

    /**
     * error：普通变更（未达售罄态、变更前亦非售罄态）——不发布任何
     * 事件。
     */
    @Test
    @DisplayName("普通变更不发布任何事件")
    void ordinaryChange_noEvent() {
        final InventoryItem item = item(5, 0, 0);
        final InventoryLog log = item.adjust("operator-1", 2, null);

        router.publishIfNeeded(log);

        verifyNoInteractions(publisher);
    }

    /**
     * 构造指定三态库存聚合（ID/SKU 固定，version 0）。
     *
     * @param available 可售量
     * @param held      预占量
     * @param sold      已售量
     * @return 库存聚合
     */
    private InventoryItem item(int available, int held, int sold) {
        return new InventoryItem(1L, 100L, 88001L, available, held, sold, 0);
    }
}