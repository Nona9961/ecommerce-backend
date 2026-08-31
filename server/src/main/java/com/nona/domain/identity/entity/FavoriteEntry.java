package com.nona.domain.identity.entity;

import java.time.LocalDateTime;

/**
 * 收藏条目（Favorite 聚合内实体，对应 favorite 表行）：
 * 买家对商品 / 店铺的单条收藏记录。
 * <p>
 * 领域模型冻结定义（favorite entry set）：Favorite 聚合 = 收藏条目集
 * （type: product/shop + target id + time），独立聚合、独立并发面；
 * 条目粒度操作（收藏/取消收藏）不加载整个集合——与 Account 聚合内
 * 账号-店铺关联的独立持久化模式同构。
 * <p>
 * 不变量：同一买家 + 同类型 + 同目标唯一（幂等，数据库 (account_id,
 * favorite_type, target_id) 联合唯一约束兜底）；目标 ID 为引用 ID
 * （目标实体属目录域，本域不校验存在性、不建外键）。
 * 创建必须经由 {@link com.nona.domain.identity.factory.FavoriteFactory}
 * + {@link com.nona.util.IDUtils#generateID()}；收藏时间由持久化层
 * 审计字段回填（新建时为 null）。
 *
 * @author nona9961
 */
public class FavoriteEntry {

    /**
     * 条目 ID（Snowflake）
     */
    private final Long id;

    /**
     * 买家账号 ID（归属不变量）
     */
    private final Long accountId;

    /**
     * 收藏目标类型（商品 / 店铺）
     */
    private final FavoriteType favoriteType;

    /**
     * 收藏目标 ID（引用 ID）
     */
    private final Long targetId;

    /**
     * 收藏时间（持久化层回填；新建条目为 null）
     */
    private final LocalDateTime createTime;

    /**
     * 构造收藏条目（仅 Factory 调用）。
     *
     * @param id           条目 ID
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param targetId     收藏目标 ID
     * @param createTime   收藏时间（新建为 null）
     */
    public FavoriteEntry(Long id, Long accountId, FavoriteType favoriteType, Long targetId, LocalDateTime createTime) {
        this.id = id;
        this.accountId = accountId;
        this.favoriteType = favoriteType;
        this.targetId = targetId;
        this.createTime = createTime;
    }

    /**
     * 条目 ID。
     *
     * @return 条目 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 收藏目标类型。
     *
     * @return 收藏目标类型
     */
    public FavoriteType getFavoriteType() {
        return favoriteType;
    }

    /**
     * 收藏目标 ID。
     *
     * @return 收藏目标 ID
     */
    public Long getTargetId() {
        return targetId;
    }

    /**
     * 收藏时间。
     *
     * @return 收藏时间；新建条目为 null
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }
}