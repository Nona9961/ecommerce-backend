package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.identity.entity.FavoriteType;
import com.nona.inf.persistence.po.identity.FavoritePO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 收藏 JPA 仓储（favorite 表，global）：按买家 + 类型 + 目标定向查重 / 删除，
 * 按买家分页查询（收藏时间倒序由调用方排序）。
 * 采用完整 JPA 仓储接口（ListCrudRepository 超集）：saveAndFlush 用于并发
 * 重复收藏的唯一约束冲突即时捕获（延迟 flush 会使冲突越过仓储捕获点）。
 *
 * @author nona9961
 */
public interface FavoriteJpaRepository extends JpaRepository<FavoritePO, Long> {

    /**
     * 按买家 + 类型 + 目标查询收藏条目（联合唯一约束保证至多一行）。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param targetId     收藏目标 ID
     * @return 收藏条目；不存在返回空
     */
    Optional<FavoritePO> findByAccountIdAndFavoriteTypeAndTargetId(Long accountId, FavoriteType favoriteType, Long targetId);

    /**
     * 按类型分页查询买家的收藏。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param pageable     分页与排序
     * @return 分页收藏条目
     */
    Page<FavoritePO> findByAccountIdAndFavoriteType(Long accountId, FavoriteType favoriteType, Pageable pageable);

    /**
     * 分页查询买家的全部收藏（不区分类型）。
     *
     * @param accountId 买家账号 ID
     * @param pageable  分页与排序
     * @return 分页收藏条目
     */
    Page<FavoritePO> findByAccountId(Long accountId, Pageable pageable);

    /**
     * 按类型统计买家的收藏条数。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @return 条数
     */
    long countByAccountIdAndFavoriteType(Long accountId, FavoriteType favoriteType);

    /**
     * 统计买家的全部收藏条数（不区分类型）。
     *
     * @param accountId 买家账号 ID
     * @return 条数
     */
    long countByAccountId(Long accountId);

    /**
     * 按买家 + 类型 + 目标删除收藏条目（联合唯一约束保证至多一行）。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param targetId     收藏目标 ID
     * @return 删除的条数（0 或 1）
     */
    long deleteByAccountIdAndFavoriteTypeAndTargetId(Long accountId, FavoriteType favoriteType, Long targetId);
}