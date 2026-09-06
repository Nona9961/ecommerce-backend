package com.nona.domain.logistics.entity;

import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.time.LocalDateTime;

/**
 * 运单轨迹条目（waybill_track 从表行，append-only 实体）：时间线上
 * 的一个节点（状态/时间/描述）。
 * <p>
 * 语义：
 * <ul>
 *     <li>append-only：轨迹一经产生不可变更（全部字段 final，无任何
 *         变更路径/方法）；轨迹只经聚合方法追加（{@link Waybill#advanceTo}），
 *         外部无修改入口；</li>
 *     <li>归属：waybillId（rootId）指向所属运单（from 表以 rootId 关联
 *         聚合根）；轨迹条目的状态 = 该节点发生时运单所处的状态，与
 *         运单当前状态的一致性由 {@link Waybill} 构造守卫校验（末条轨迹
 *         状态 == 运单当前状态）；</li>
 *     <li>描述可空：非必要字段（如「已到达 XX 中转站」），缺失允许。</li>
 * </ul>
 * 创建必须经由 {@link com.nona.domain.logistics.factory.WaybillFactory#createTrack}
 * （ID 生成 + 归属绑定）；装载（仓储重建）走全量构造器。
 *
 * @author nona9961
 */
public class WaybillTrack {

    /**
     * 轨迹主键（Snowflake，waybill_track 主键，独立于运单主键）
     */
    private final Long id;

    /**
     * 归属运单 ID（rootId 关联，从表外键语义）
     */
    private final Long waybillId;

    /**
     * 该节点发生时运单所处的状态（append-only 时间线状态快照）
     */
    private final WaybillStatus status;

    /**
     * 节点发生时间
     */
    private final LocalDateTime occurredAt;

    /**
     * 节点描述（可空：如物流公司推送的节点文案）
     */
    private final String description;

    /**
     * 构造轨迹条目（创建/装载共用）：形态不变量守卫（主键/归属/状态/
     * 时间必填——缺失关键字段的时间线条目无业务意义；描述可空）。
     *
     * @param id          轨迹主键（必填）
     * @param waybillId   归属运单 ID（必填）
     * @param status      节点状态（必填）
     * @param occurredAt  发生时间（必填）
     * @param description 节点描述（可空）
     */
    public WaybillTrack(Long id, Long waybillId, WaybillStatus status,
                        LocalDateTime occurredAt, String description) {
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code(),
                id, "轨迹主键不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code(),
                waybillId, "轨迹归属运单 ID 不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code(),
                status, "轨迹状态不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code(),
                occurredAt, "轨迹发生时间不能为空");
        this.id = id;
        this.waybillId = waybillId;
        this.status = status;
        this.occurredAt = occurredAt;
        this.description = description;
    }

    /**
     * 轨迹主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属运单 ID（rootId 关联）。
     *
     * @return 运单 ID
     */
    public Long getWaybillId() {
        return waybillId;
    }

    /**
     * 节点发生时的运单状态。
     *
     * @return 状态
     */
    public WaybillStatus getStatus() {
        return status;
    }

    /**
     * 节点发生时间。
     *
     * @return 时间
     */
    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    /**
     * 节点描述（可空）。
     *
     * @return 描述；无描述为 null
     */
    public String getDescription() {
        return description;
    }
}