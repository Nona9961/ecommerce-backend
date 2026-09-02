package com.nona.inf.persistence.po.catalog;

import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 品牌持久化对象（brand 表，global）：品牌聚合根主表。
 * <p>
 * 平台品牌库与租户无关（不随店铺隔离，全局可见）；名称全局唯一
 * （uk_brand_name，含禁用态不可复用）。状态 ENABLED/DISABLED 承载
 * 「删除」= 禁用软删语义（disable-not-delete，行保留）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "brand", uniqueConstraints = {
        @UniqueConstraint(name = "uk_brand_name", columnNames = {"name"})
})
public class BrandPO extends BasePO {

    /**
     * 品牌名称（全局唯一）
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 品牌 logo URL（可空）
     */
    @Column(length = 512)
    private String logo;

    /**
     * 品牌状态（ENABLED / DISABLED）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BrandStatus status;

    /**
     * 品牌名称。
     *
     * @return 品牌名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置品牌名称。
     *
     * @param name 品牌名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 品牌 logo。
     *
     * @return logo；未设置返回 null
     */
    public String getLogo() {
        return logo;
    }

    /**
     * 设置品牌 logo。
     *
     * @param logo logo URL；null 清除
     */
    public void setLogo(String logo) {
        this.logo = logo;
    }

    /**
     * 品牌状态。
     *
     * @return 品牌状态
     */
    public BrandStatus getStatus() {
        return status;
    }

    /**
     * 设置品牌状态。
     *
     * @param status 品牌状态
     */
    public void setStatus(BrandStatus status) {
        this.status = status;
    }
}