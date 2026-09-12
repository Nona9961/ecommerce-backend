package com.nona.domain.logistics.ports;

import com.nona.events.Event;

import java.time.Instant;

/**
 * 运单签收领域事件（logistics 域发布 → 订单域消费自动确认收货联动）：
 * 模拟推进器将运单推进至已签收（终态）即发布——签收是物流侧「货物已
 * 妥投」的事实锚点，订单域消费侧据此触发系统侧自动完成（已发货子单
 * 完成推进，与买家确认收货共用同一迁移，触发源由调用方语义区分）。
 * <p>
 * 载荷设计：waybillId + subOrderId（最小定位引用）——签收运单定位 +
 * 订单侧操作单元（子单）；消费方完成联动所需其余资料（归属主单等）
 * 可经 subOrderId 回查，不占用事件契约演进面（SelloutEvent 最小载荷
 * 同构）。
 * <p>
 * 发布语义：进程内事件、推进事务提交后（AFTER_COMMIT）投递——消费方
 * 仅看到已提交的签收状态（自动完成联动为最终一致联动，延迟可接受）；
 * 事件不承担可靠性职责（可靠面由同库事务与变更外发承担）。
 * <p>
 * 不重复语义：模拟推进器终态闭合（已签收运单不再推进、不重复发布），
 * 事件对同一运单至多发布一次；异常重试/重扫产生的重复事件由消费侧
 * 幂等短路兜底。
 *
 * @author nona9961
 */
public final class WaybillDelivered implements Event<WaybillDelivered.WaybillDeliveredData> {

    /**
     * 事件类型标识（与消费方契约一致；Spring 事件按类型匹配，本常量
     * 供日志与调试定位）
     */
    public static final String TYPE = "WaybillDelivered";

    /**
     * 事件载荷：签收运单定位引用
     *
     * @param waybillId  签收运单 ID（必填；事件主体定位）
     * @param subOrderId 签收运单关联子单 ID（必填；订单侧操作单元定位）
     */
    public record WaybillDeliveredData(Long waybillId, Long subOrderId) {
    }

    /**
     * 事件载荷
     */
    private final WaybillDeliveredData payload;

    /**
     * 事件发生时间戳（签收推进通过、事件构造时点）
     */
    private final Instant timestamp;

    /**
     * 构造签收事件。
     *
     * @param waybillId  签收运单 ID（必填）
     * @param subOrderId 签收运单关联子单 ID（必填）
     */
    public WaybillDelivered(Long waybillId, Long subOrderId) {
        this.payload = new WaybillDeliveredData(waybillId, subOrderId);
        this.timestamp = Instant.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WaybillDeliveredData getPayload() {
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