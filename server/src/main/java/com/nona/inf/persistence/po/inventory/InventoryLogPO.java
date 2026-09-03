package com.nona.inf.persistence.po.inventory;

import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 库存流水持久化对象（inventory_log 表，tenant=shopId）：InventoryItem
 * 聚合的 append-only 从表行（每笔库存变更一行，只增不改——行级删除语义
 * 不存在）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离——
 * 跨店铺 SKU 流水行在 Hibernate 租户过滤层即被拦截。sku_id 为业务关联列
 * （跨域引用 SKU）；type 为流水类型（方向语义见 {@link InventoryLogType}）；
 * delta 为变动数量（订单驱动型恒正、手动调整型带符号，非零）；order_id
 * 为订单上下文（订单驱动型必填、手动调整型恒空）；before/after 各为
 * 三态全量快照（可售/预占/已售，任意一行可独立复现该 SKU 该时点库存
 * 全貌）；operator 为操作人（手动调整型必填，订单驱动型可空）；
 * reason 为调整原因（可空）。幂等键 (order_id, sku_id, type) 唯一约束
 * （uk_inventory_log_order_sku_type）——同一订单同一 SKU 的同一类型变动
 * 只允许一次，重复插入由 DB 拒绝（防重复预占/扣减/回滚；手动调整型无
 * orderId，不参与订单幂等——唯一约束对 NULL 不生效，多行合法共存）。
 * 创建时间承载流水产生时间（create_time 审计列，新行在前按 ID 倒序）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "inventory_log", uniqueConstraints = {
        @UniqueConstraint(name = "uk_inventory_log_order_sku_type",
                columnNames = {"order_id", "sku_id", "type"})
}, indexes = {
        @Index(name = "idx_inventory_log_sku", columnList = "sku_id")
})
public class InventoryLogPO extends TenantScopedBasePO {

    /**
     * 归属 SKU ID（跨域引用键）
     */
    @Column(nullable = false, name = "sku_id")
    private Long skuId;

    /**
     * 流水类型（PREOCCUPY / CONFIRM / ROLLBACK / MANUAL_ADJUST / REFUND_RESTORE）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InventoryLogType type;

    /**
     * 变动数量（非零；订单驱动型恒正，手动调整型带符号）
     */
    @Column(nullable = false)
    private Integer delta;

    /**
     * 订单 ID（订单驱动型必填；手动调整型恒空，参与幂等键）
     */
    @Column(name = "order_id")
    private Long orderId;

    /**
     * 变动前可售量
     */
    @Column(nullable = false, name = "before_available")
    private Integer beforeAvailable;

    /**
     * 变动前预占量
     */
    @Column(nullable = false, name = "before_held")
    private Integer beforeHeld;

    /**
     * 变动前已售量
     */
    @Column(nullable = false, name = "before_sold")
    private Integer beforeSold;

    /**
     * 变动后可售量
     */
    @Column(nullable = false, name = "after_available")
    private Integer afterAvailable;

    /**
     * 变动后预占量
     */
    @Column(nullable = false, name = "after_held")
    private Integer afterHeld;

    /**
     * 变动后已售量
     */
    @Column(nullable = false, name = "after_sold")
    private Integer afterSold;

    /**
     * 操作人（手动调整型必填；订单驱动型可空）
     */
    @Column(length = 64)
    private String operator;

    /**
     * 调整原因（可空）
     */
    @Column(length = 512)
    private String reason;

    /**
     * 归属 SKU ID。
     *
     * @return SKU ID
     */
    public Long getSkuId() {
        return skuId;
    }

    /**
     * 设置归属 SKU ID。
     *
     * @param skuId SKU ID
     */
    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    /**
     * 流水类型。
     *
     * @return 流水类型
     */
    public InventoryLogType getType() {
        return type;
    }

    /**
     * 设置流水类型。
     *
     * @param type 流水类型
     */
    public void setType(InventoryLogType type) {
        this.type = type;
    }

    /**
     * 变动数量。
     *
     * @return 变动数量
     */
    public Integer getDelta() {
        return delta;
    }

    /**
     * 设置变动数量。
     *
     * @param delta 变动数量
     */
    public void setDelta(Integer delta) {
        this.delta = delta;
    }

    /**
     * 订单 ID。
     *
     * @return 订单 ID；手动调整型为 null
     */
    public Long getOrderId() {
        return orderId;
    }

    /**
     * 设置订单 ID。
     *
     * @param orderId 订单 ID；null=手动调整型
     */
    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    /**
     * 变动前可售量。
     *
     * @return 变动前可售量
     */
    public Integer getBeforeAvailable() {
        return beforeAvailable;
    }

    /**
     * 设置变动前可售量。
     *
     * @param beforeAvailable 变动前可售量
     */
    public void setBeforeAvailable(Integer beforeAvailable) {
        this.beforeAvailable = beforeAvailable;
    }

    /**
     * 变动前预占量。
     *
     * @return 变动前预占量
     */
    public Integer getBeforeHeld() {
        return beforeHeld;
    }

    /**
     * 设置变动前预占量。
     *
     * @param beforeHeld 变动前预占量
     */
    public void setBeforeHeld(Integer beforeHeld) {
        this.beforeHeld = beforeHeld;
    }

    /**
     * 变动前已售量。
     *
     * @return 变动前已售量
     */
    public Integer getBeforeSold() {
        return beforeSold;
    }

    /**
     * 设置变动前已售量。
     *
     * @param beforeSold 变动前已售量
     */
    public void setBeforeSold(Integer beforeSold) {
        this.beforeSold = beforeSold;
    }

    /**
     * 变动后可售量。
     *
     * @return 变动后可售量
     */
    public Integer getAfterAvailable() {
        return afterAvailable;
    }

    /**
     * 设置变动后可售量。
     *
     * @param afterAvailable 变动后可售量
     */
    public void setAfterAvailable(Integer afterAvailable) {
        this.afterAvailable = afterAvailable;
    }

    /**
     * 变动后预占量。
     *
     * @return 变动后预占量
     */
    public Integer getAfterHeld() {
        return afterHeld;
    }

    /**
     * 设置变动后预占量。
     *
     * @param afterHeld 变动后预占量
     */
    public void setAfterHeld(Integer afterHeld) {
        this.afterHeld = afterHeld;
    }

    /**
     * 变动后已售量。
     *
     * @return 变动后已售量
     */
    public Integer getAfterSold() {
        return afterSold;
    }

    /**
     * 设置变动后已售量。
     *
     * @param afterSold 变动后已售量
     */
    public void setAfterSold(Integer afterSold) {
        this.afterSold = afterSold;
    }

    /**
     * 操作人。
     *
     * @return 操作人；订单驱动型为 null
     */
    public String getOperator() {
        return operator;
    }

    /**
     * 设置操作人。
     *
     * @param operator 操作人；null=订单驱动型
     */
    public void setOperator(String operator) {
        this.operator = operator;
    }

    /**
     * 调整原因。
     *
     * @return 调整原因；无原因为 null
     */
    public String getReason() {
        return reason;
    }

    /**
     * 设置调整原因。
     *
     * @param reason 调整原因；null=无原因
     */
    public void setReason(String reason) {
        this.reason = reason;
    }
}