package com.nona.inf.persistence.po.catalog;

import com.nona.inf.persistence.po.TenantScopedBasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 店铺分类持久化对象（shop_category 表，tenant=shopId）：店铺聚合的从表行。
 * <p>
 * 商家维度数据归店铺租户（tenant_id=shopId），天然 fail-closed 隔离——
 * 跨店铺访问分类行在 Hibernate 租户过滤层即被拦截。shop_id 为业务关联列
 * （rootId 关联 shop 主表，冗余承载归属便于店铺维度查询）；order_no 为
 * 展示排序（聚合内分配，删除不重排）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "shop_category", indexes = {
        @Index(name = "idx_shop_category_shop", columnList = "shop_id")
})
public class ShopCategoryPO extends TenantScopedBasePO {

    /**
     * 所属店铺 ID（rootId 关联）
     */
    @Column(nullable = false, name = "shop_id")
    private Long shopId;

    /**
     * 分类名称
     */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 展示排序（聚合内分配）
     */
    @Column(nullable = false, name = "order_no")
    private int orderNo;

    /**
     * 所属店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 设置所属店铺 ID。
     *
     * @param shopId 店铺 ID
     */
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

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
}