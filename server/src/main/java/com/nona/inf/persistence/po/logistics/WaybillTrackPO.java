package com.nona.inf.persistence.po.logistics;

import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 运单轨迹条目持久化对象（waybill_track 从表，global）：时间线上的一
 * 个节点（状态/时间/描述），append-only 追加（行追加不更新不删除）。
 * <p>
 * 归属 waybill 主键（waybill_id，rootId 关联）；occurred_at 为节点
 * 发生时间（领域 LocalDateTime 直映射，datetime(6) 列）；description
 * 节点描述可空（如物流公司推送的节点文案）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "waybill_track", indexes = {
        @Index(name = "idx_waybill_track_waybill", columnList = "waybill_id")
})
public class WaybillTrackPO extends BasePO {

    /**
     * 归属运单主键（rootId 关联）
     */
    @Column(nullable = false, name = "waybill_id")
    private Long waybillId;

    /**
     * 节点发生时运单所处的状态（append-only 时间线状态快照）
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private WaybillStatus status;

    /**
     * 节点发生时间（datetime(6) 直映射）
     */
    @Column(nullable = false, name = "occurred_at")
    private java.time.LocalDateTime occurredAt;

    /**
     * 节点描述（可空：如「已到达 XX 中转站」）
     */
    @Column(length = 255)
    private String description;

    public Long getWaybillId() {
        return waybillId;
    }

    public void setWaybillId(Long waybillId) {
        this.waybillId = waybillId;
    }

    public WaybillStatus getStatus() {
        return status;
    }

    public void setStatus(WaybillStatus status) {
        this.status = status;
    }

    public java.time.LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(java.time.LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}