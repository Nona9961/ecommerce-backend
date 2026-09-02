package com.nona.inf.persistence.po.catalog;

import com.nona.domain.catalog.entity.ShopStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 店铺持久化对象（shop 表，global）：店铺聚合根主表。
 * <p>
 * 店铺是租户（tenantID=shopId）的来源，主表不自引用 tenant 列——店铺行
 * global 可见（平台/买家经 {@code @CrossTenant} 或直读展示），从表
 * {@link ShopCategoryPO} 为 tenant-scoped（tenant=shopId）承载商家维度数据。
 * 主键 id = 店铺 ID（Snowflake），即商家数据租户锚点。
 *
 * @author nona9961
 */
@Entity
@Table(name = "shop")
public class ShopPO extends BasePO {

    /**
     * 店铺名称
     */
    @Column(nullable = false, length = 128)
    private String name;

    /**
     * 店铺 logo（可空）
     */
    @Column(length = 512)
    private String logo;

    /**
     * 店铺简介（可空）
     */
    @Column(length = 2000)
    private String description;

    /**
     * 店铺状态（NORMAL / FROZEN）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShopStatus status;

    /**
     * 店铺名称。
     *
     * @return 店铺名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置店铺名称。
     *
     * @param name 店铺名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 店铺 logo。
     *
     * @return logo；未设置返回 null
     */
    public String getLogo() {
        return logo;
    }

    /**
     * 设置店铺 logo。
     *
     * @param logo 店铺 logo
     */
    public void setLogo(String logo) {
        this.logo = logo;
    }

    /**
     * 店铺简介。
     *
     * @return 简介；未设置返回 null
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置店铺简介。
     *
     * @param description 店铺简介
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 店铺状态。
     *
     * @return 店铺状态
     */
    public ShopStatus getStatus() {
        return status;
    }

    /**
     * 设置店铺状态。
     *
     * @param status 店铺状态
     */
    public void setStatus(ShopStatus status) {
        this.status = status;
    }
}