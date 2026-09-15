package com.nona.inf.persistence.po.logistics;

import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 运单聚合根持久化对象（waybill 表，global——租户中立，经 sub_order_id
 * 业务关联订单域子单，不建跨域外键）聚合根主表。
 * <p>
 * 主键 id = 运单独立主键（Snowflake，全局唯一）；sub_order_id 业务关联
 * 列 + in_transit 派生态列构成的复合唯一约束 (sub_order_id, in_transit)
 * 承载「一子单一在途」的 DB 物理防线——在途（in_transit=TRUE）行每
 * 子单至多一张，重复发货在 DB 层被拒；签收后该行置 NULL（历史行
 * NULL 允许多条并存，子单再次发货不冲突）——MySQL 唯一索引对 NULL
 * 多行放行的常规模拟形态。company/tracking_no 录单必填非空列；status
 * 状态列（单向相邻推进收敛在聚合方法 advanceTo）。从表 waybill_track
 * 以 waybill_id（rootId）关联。
 *
 * @author nona9961
 */
@Entity
@Table(name = "waybill", uniqueConstraints = {
        @UniqueConstraint(name = "uk_waybill_sub_order_in_transit",
                columnNames = {"sub_order_id", "in_transit"})
})
public class WaybillPO extends BasePO {

    /**
     * 关联子单 ID（业务关联列，在途唯一锚点）
     */
    @Column(nullable = false, name = "sub_order_id")
    private Long subOrderId;

    /**
     * 承运公司（录单必填，创建定型不可变）
     */
    @Column(nullable = false, length = 64)
    private String company;

    /**
     * 运单号（录单必填，仓配唯一业务凭证）
     */
    @Column(nullable = false, length = 64, name = "tracking_no")
    private String trackingNo;

    /**
     * 履约状态（状态机唯一可变位，迁移经聚合方法 advanceTo）
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private WaybillStatus status;

    /**
     * 在途派生态（status != DELIVERED 即在途；转换器由状态推导写入，
     * 唯一约束组成——NULL 行允许多条历史，1 行每子单至多一张）。
     * 可空承载：NULL = 历史行（签收释放锚点），MySQL 唯一索引
     * NULL 多行放行。
     */
    @Column(name = "in_transit")
    private Boolean inTransit;

    public Long getSubOrderId() {
        return subOrderId;
    }

    public void setSubOrderId(Long subOrderId) {
        this.subOrderId = subOrderId;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public WaybillStatus getStatus() {
        return status;
    }

    public void setStatus(WaybillStatus status) {
        this.status = status;
    }

    public Boolean getInTransit() {
        return inTransit;
    }

    public void setInTransit(Boolean inTransit) {
        this.inTransit = inTransit;
    }
}