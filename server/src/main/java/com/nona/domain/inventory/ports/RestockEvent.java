package com.nona.domain.inventory.ports;

import com.nona.events.Event;

import java.time.Instant;

/**
 * 可售恢复领域事件（inventory 域发布，一期为日志型消费）：手工调整
 * 把此前已售罄（available=0 且 held=0）的 SKU 恢复为可售（调整后
 * available>0）即发布——「此前已售罄」由变更前后快照判定（变更前
 * 已售罄且变更后未售罄），不依赖任何状态追踪。
 * <p>
 * 消费语义：一期仅日志留痕（可售恢复不触发自动上架——上架属商品域
 * 商家显式动作）；载荷与发布语义同 {@link SelloutEvent}（仅 skuId、
 * 进程内事件、库存事务提交后投递）。
 *
 * @author nona9961
 */
public final class RestockEvent implements Event<RestockEvent.RestockData> {

    /**
     * 事件类型标识（与消费方契约一致；Spring 事件按类型匹配，本常量
     * 供日志与调试定位）
     */
    public static final String TYPE = "Restock";

    /**
     * 事件载荷：恢复可售的 SKU
     *
     * @param skuId 恢复可售的 SKU ID（必填）
     */
    public record RestockData(Long skuId) {
    }

    /**
     * 事件载荷
     */
    private final RestockData payload;

    /**
     * 事件发生时间戳（判定通过、事件构造时点）
     */
    private final Instant timestamp;

    /**
     * 构造可售恢复事件。
     *
     * @param skuId 恢复可售的 SKU ID（必填）
     */
    public RestockEvent(Long skuId) {
        this.payload = new RestockData(skuId);
        this.timestamp = Instant.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RestockData getPayload() {
        return payload;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant timestamp() {
        return timestamp;
    }
}