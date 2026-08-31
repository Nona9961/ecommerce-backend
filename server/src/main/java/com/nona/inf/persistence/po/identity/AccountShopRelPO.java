package com.nona.inf.persistence.po.identity;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 账号-店铺关联持久化对象（account_shop_rel 表，global）：账号-店铺多对多关联，一期每商家一行。
 *
 * @author nona9961
 */
@Entity
@Table(name = "account_shop_rel", uniqueConstraints = {
        @UniqueConstraint(name = "uk_account_shop_rel", columnNames = {"account_id", "shop_id"})
})
public class AccountShopRelPO extends BasePO {

    /**
     * 账号 ID
     */
    @Column(nullable = false, name = "account_id")
    private Long accountId;

    /**
     * 店铺 ID
     */
    @Column(nullable = false, name = "shop_id")
    private Long shopId;

    /**
     * 账号 ID。
     *
     * @return 账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置账号 ID。
     *
     * @param accountId 账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /**
     * 店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 设置店铺 ID。
     *
     * @param shopId 店铺 ID
     */
    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }
}
