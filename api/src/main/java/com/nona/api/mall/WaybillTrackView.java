package com.nona.api.mall;

/**
 * 物流轨迹视图（运单追加记录，WU-59 回注——形状钉死前端
 * mall-trading.types.ts {@code WaybillTrackView}）。
 * <p>
 * 时间形态：occurredAt 为 ISO-8601 字符串（WaybillTrack.occurredAt
 * LocalDateTime 序列化——UTC 语义与 PO 列一致）。
 *
 * @param status      推进后状态（MallWaybillStatus 4 值，枚举名序列化）
 * @param occurredAt  推进时刻（ISO-8601 字符串）
 * @param description 推进描述（可空；如「包裹已揽收」）
 * @author nona9961
 */
public record WaybillTrackView(
        MallWaybillStatus status,
        String occurredAt,
        String description
) {
}