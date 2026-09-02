package com.nona.inf.persistence.po.catalog;

import com.nona.domain.catalog.entity.CategoryStatus;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 平台分类持久化对象（platform_category 表，global）：平台一级分类聚合根主表。
 * <p>
 * 平台货架组织单位，与租户无关（不随店铺隔离，全局可见）；名称全局唯一
 * （uk_platform_category_name，含禁用态不可复用）。展示排序存 order_no 列
 * （order 为保留语义字，避免列名歧义）；状态 ENABLED/DISABLED 承载
 * 「删除」= 禁用软删语义（disable-not-delete，行保留）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "platform_category", uniqueConstraints = {
        @UniqueConstraint(name = "uk_platform_category_name", columnNames = {"name"})
})
public class PlatformCategoryPO extends BasePO {

    /**
     * 分类名称（全局唯一）
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 展示排序（正数；列表按此升序）
     */
    @Column(nullable = false, name = "order_no")
    private int orderNo;

    /**
     * 分类状态（ENABLED / DISABLED）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CategoryStatus status;

    /**
     * 分类名称。
     *
     * @return 分类名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置分类名称。
     *
     * @param name 分类名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 展示排序。
     *
     * @return 排序值
     */
    public int getOrderNo() {
        return orderNo;
    }

    /**
     * 设置展示排序。
     *
     * @param orderNo 排序值
     */
    public void setOrderNo(int orderNo) {
        this.orderNo = orderNo;
    }

    /**
     * 分类状态。
     *
     * @return 分类状态
     */
    public CategoryStatus getStatus() {
        return status;
    }

    /**
     * 设置分类状态。
     *
     * @param status 分类状态
     */
    public void setStatus(CategoryStatus status) {
        this.status = status;
    }
}