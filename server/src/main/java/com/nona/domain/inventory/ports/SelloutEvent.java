package com.nona.domain.inventory.ports;

import com.nona.events.Event;

import java.time.Instant;

/**
 * 售罄领域事件（inventory 域发布 → 商品域消费自动下架，日志型
 * 消费）：任何库存变更操作（预占/扣减/手工调整）导致 available=0 且
 * held=0 即发布——售罄定义收敛在判定点，本事件仅承载最小定位信息。
 * <p>
 * 载荷设计：仅 skuId（消费方自动下架只需定位 SKU，其余资料可经 SKU
 * 回查；触发操作类型对下架动作无信息增益，不占用事件契约演进面）。
 * 发布语义：进程内事件、库存事务提交后（AFTER_COMMIT）投递——消费方
 * 仅看到已提交的库存状态（自动下架为最终一致联动，延迟可接受）。
 *
 * @author nona9961
 */
public final class SelloutEvent implements Event<SelloutEvent.SelloutData> {

    /**
     * 事件类型标识（与消费方契约一致；Spring 事件按类型匹配，本常量
     * 供日志与调试定位）
     */
    public static final String TYPE = "Sellout";

    /**
     * 事件载荷：售罄 SKU
     *
     * @param skuId 售罄 SKU ID（必填）
     */
    public record SelloutData(Long skuId) {
    }

    /**
     * 事件载荷
     */
    private final SelloutData payload;

    /**
     * 事件发生时间戳（判定通过、事件构造时点）
     */
    private final Instant timestamp;

    /**
     * 构造售罄事件。
     *
     * @param skuId 售罄 SKU ID（必填）
     */
    public SelloutEvent(Long skuId) {
        this.payload = new SelloutData(skuId);
        this.timestamp = Instant.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SelloutData getPayload() {
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