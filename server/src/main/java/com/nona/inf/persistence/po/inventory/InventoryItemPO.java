package com.nona.inf.persistence.po.inventory;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 库存聚合根持久化对象（inventory_item 表，tenant=shopId）：InventoryItem
 * 聚合根主表（一行 = 一个 SKU 库存聚合实例，无集合子实体——流水独立成
 * 表，见 InventoryLogPO）。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId，店铺 ID 即租户 ID），天然
 * fail-closed 隔离——跨店铺 SKU 库存行在 Hibernate 租户过滤层即被拦截，
 * 按不存在呈现。sku_id 为业务唯一列（uk_inventory_item_sku 唯一约束兜底
 * 重复初始化冲突——并发同 SKU 初始化仅一行成功）；available/held/sold
 * 为三态数量（可售/预占/已售，恒非负——负值由聚合构造守卫与变更守卫
 * 拒绝）；version 为乐观锁版本（初始 0，每笔变更 +1，随变更落库推进，
 * 仅承载手工调整/对账的冲突检测辅助，不参与防超卖条件更新——条件更新
 * 属后续阶段）。tenant 归属在本表不冗余 shop_id 列：店铺维度即租户维度
 * （tenant_id=shopId），读回时租户列解析为店铺归属。
 *
 * @author nona9961
 */
@Entity
@Table(name = "inventory_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_inventory_item_sku", columnNames = {"sku_id"})
})
public class InventoryItemPO extends TenantScopedBasePO {

    /**
     * 归属 SKU ID（业务唯一列：一个 SKU 一行库存）
     */
    @Column(nullable = false, name = "sku_id")
    private Long skuId;

    /**
     * 可售量（三态之一，恒非负）
     */
    @Column(nullable = false)
    private Integer available;

    /**
     * 预占量（三态之一，恒非负）
     */
    @Column(nullable = false)
    private Integer held;

    /**
     * 已售量（三态之一，恒非负）
     */
    @Column(nullable = false)
    private Integer sold;

    /**
     * 乐观锁版本（初始 0，每笔变更 +1）
     */
    @Column(nullable = false)
    private Integer version;

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
     * 可售量。
     *
     * @return 可售量
     */
    public Integer getAvailable() {
        return available;
    }

    /**
     * 设置可售量。
     *
     * @param available 可售量
     */
    public void setAvailable(Integer available) {
        this.available = available;
    }

    /**
     * 预占量。
     *
     * @return 预占量
     */
    public Integer getHeld() {
        return held;
    }

    /**
     * 设置预占量。
     *
     * @param held 预占量
     */
    public void setHeld(Integer held) {
        this.held = held;
    }

    /**
     * 已售量。
     *
     * @return 已售量
     */
    public Integer getSold() {
        return sold;
    }

    /**
     * 设置已售量。
     *
     * @param sold 已售量
     */
    public void setSold(Integer sold) {
        this.sold = sold;
    }

    /**
     * 乐观锁版本。
     *
     * @return 版本号
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * 设置乐观锁版本。
     *
     * @param version 版本号
     */
    public void setVersion(Integer version) {
        this.version = version;
    }
}