package com.nona.inf.persistence.po.order;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 买家购物车聚合根持久化对象（cart 表，global）：聚合根主表。
 * <p>
 * 主键 id = 购物车独立主键（Snowflake，全局唯一）；buyer_id 为业务关联列
 * （一个买家一个购物车，唯一约束）。从表 {@link CartItemPO} 以 cart_id
 * 关联本根行。买家维度数据全局可见（不随店铺租户隔离）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "cart", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_buyer", columnNames = "buyer_id")
})
public class CartPO extends BasePO {

    /**
     * 归属买家账号 ID（业务关联列，一个买家一个购物车）
     */
    @Column(nullable = false, name = "buyer_id")
    private Long buyerId;

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getBuyerId() {
        return buyerId;
    }

    /**
     * 设置归属买家账号 ID。
     *
     * @param buyerId 买家账号 ID
     */
    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
    }
}