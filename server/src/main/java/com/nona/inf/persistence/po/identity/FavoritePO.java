package com.nona.inf.persistence.po.identity;

import com.nona.domain.identity.entity.FavoriteType;
import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 收藏持久化对象（favorite 表，global）：买家对商品 / 店铺的收藏条目。
 * <p>
 * 幂等不变量以联合唯一约束落地：(account_id, favorite_type, target_id) 唯一
 * （同一买家 + 同类型 + 同目标至多一行）；目标 ID 为引用 ID（目标实体属
 * 目录域，本表不建外键）。买家数据全局可见（不随店铺租户隔离）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "favorite", uniqueConstraints = {
        @UniqueConstraint(name = "uk_favorite_account_type_target",
                columnNames = {"account_id", "favorite_type", "target_id"})
})
public class FavoritePO extends BasePO {

    /**
     * 买家账号 ID（归属）
     */
    @Column(nullable = false, name = "account_id")
    private Long accountId;

    /**
     * 收藏目标类型（PRODUCT / SHOP）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, name = "favorite_type")
    private FavoriteType favoriteType;

    /**
     * 收藏目标 ID（引用 ID，不建外键）
     */
    @Column(nullable = false, name = "target_id")
    private Long targetId;

    /**
     * 买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置买家账号 ID。
     *
     * @param accountId 买家账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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
     * 设置收藏目标类型。
     *
     * @param favoriteType 收藏目标类型
     */
    public void setFavoriteType(FavoriteType favoriteType) {
        this.favoriteType = favoriteType;
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
     * 设置收藏目标 ID。
     *
     * @param targetId 收藏目标 ID
     */
    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }
}