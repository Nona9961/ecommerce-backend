package com.nona.domain.order.ports;

import com.nona.events.Event;

import java.time.Instant;

/**
 * 子单完成领域事件（order 域发布，当前仅日志型消费）：子单履约完成
 * （已发货 → 已完成，确认收货 / 收货超时自动完成共用同一迁移）即发布——完成迁移是 SubOrder 聚合的真实
 * 状态迁移点，主单 COMPLETED 为派生投影（非独立迁移），故事件以
 * <b>子单为粒度</b>每次完成发布一次；多子单主单中最后一个子单完成的
 * 发布时点即主单整体完成时点，消费方无需主单级独立事件。
 * <p>
 * 载荷设计：仅携带定位引用 ID（subOrderId + masterOrderId）——消费方
 * （评价资格授予/结算入账/通知/统计）可经 subOrderId 回查子单（订单项/
 * 金额/店铺），masterOrderId 供主单维度聚合消费定位；完成来源
 * （买家人工确认 vs 系统超时）对消费方行为无信息增益，不占用事件契约
 * 演进面（SelloutEvent 最小载荷同构）。
 * <p>
 * 发布语义：进程内事件、订单事务提交后（AFTER_COMMIT）投递——消费方
 * 仅看到已提交的完成状态（最终一致联动，延迟可接受）；事件不承担
 * 可靠性职责（可靠面由同库事务与变更外发承担）。
 *
 * @author nona9961
 */
public final class OrderCompleted implements Event<OrderCompleted.OrderCompletedData> {

    /**
     * 事件类型标识（与消费方契约一致；Spring 事件按类型匹配，本常量
     * 供日志与调试定位）
     */
    public static final String TYPE = "OrderCompleted";

    /**
     * 事件载荷：完成子单定位引用 ID
     *
     * @param subOrderId    完成子单 ID（必填；事件主体定位）
     * @param masterOrderId 子单归属主单 ID（必填；主单维度聚合消费定位）
     */
    public record OrderCompletedData(Long subOrderId, Long masterOrderId) {
    }

    /**
     * 事件载荷
     */
    private final OrderCompletedData payload;

    /**
     * 事件发生时间戳（完成推进通过、事件构造时点）
     */
    private final Instant timestamp;

    /**
     * 构造完成事件。
     *
     * @param subOrderId    完成子单 ID（必填）
     * @param masterOrderId 子单归属主单 ID（必填）
     */
    public OrderCompleted(Long subOrderId, Long masterOrderId) {
        this.payload = new OrderCompletedData(subOrderId, masterOrderId);
        this.timestamp = Instant.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OrderCompletedData getPayload() {
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