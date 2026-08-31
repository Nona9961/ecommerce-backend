package com.nona.domain.identity.entity;

/**
 * 账号-店铺关联（Account 聚合内关联实体）：账号与店铺的多对多关系实体（对应 account_shop_rel 表，global）。
 * <p>
 * 一期每商家一行（入驻审核通过时绑定，登录时读取写入 Redis 用户上下文 shopIds）；
 * II 期多店铺同轨复用同一关联。创建必须经由
 * {@link com.nona.domain.identity.factory.AccountFactory} + {@link com.nona.util.IDUtils#generateID()}。
 *
 * @author nona9961
 */
public class AccountShopRel {

    /**
     * 关联 ID（Snowflake）
     */
    private final Long id;

    /**
     * 账号 ID
     */
    private final Long accountId;

    /**
     * 店铺 ID
     */
    private final Long shopId;

    /**
     * 构造账号-店铺关联（仅 Factory 调用）。
     *
     * @param id        关联 ID
     * @param accountId 账号 ID
     * @param shopId    店铺 ID
     */
    public AccountShopRel(Long id, Long accountId, Long shopId) {
        this.id = id;
        this.accountId = accountId;
        this.shopId = shopId;
    }

    /**
     * 关联 ID。
     *
     * @return 关联 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 账号 ID。
     *
     * @return 账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 店铺 ID。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }
}
