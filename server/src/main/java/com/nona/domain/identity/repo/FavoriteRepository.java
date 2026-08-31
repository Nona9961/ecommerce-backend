package com.nona.domain.identity.repo;

import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.persistence.BaseRepository;

import java.util.List;
import java.util.Optional;

/**
 * 收藏仓储接口（Favorite 聚合条目持久化契约，实现在基础设施层）。
 * <p>
 * 幂等契约：收藏查询按「买家 + 类型 + 目标」定位（联合唯一，至多一行）；
 * 取消收藏按同一谓词删除（不存在即删 0 行，幂等）；列表按买家分页、
 * 收藏时间倒序（可按类型过滤）。并发兜底：插入命中唯一约束冲突时
 * {@link #save} 返回 false（视为已存在，幂等语义不变）。
 *
 * @author nona9961
 */
public interface FavoriteRepository extends BaseRepository<Long, FavoriteEntry> {

    /**
     * 按买家 + 类型 + 目标查询收藏条目（幂等查重路径）。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param targetId     收藏目标 ID
     * @return 收藏条目；不存在返回空
     */
    Optional<FavoriteEntry> findByAccountIdAndTypeAndTargetId(Long accountId, FavoriteType favoriteType, Long targetId);

    /**
     * 按类型分页列出买家的收藏（收藏时间倒序）。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param offset       首条偏移量（从 0 开始）
     * @param limit        每页条数
     * @return 收藏条目列表；无收藏返回空列表
     */
    List<FavoriteEntry> listByAccountIdAndType(Long accountId, FavoriteType favoriteType, int offset, int limit);

    /**
     * 分页列出买家的全部收藏（收藏时间倒序，不区分类型）。
     *
     * @param accountId 买家账号 ID
     * @param offset    首条偏移量（从 0 开始）
     * @param limit     每页条数
     * @return 收藏条目列表；无收藏返回空列表
     */
    List<FavoriteEntry> listByAccountId(Long accountId, int offset, int limit);

    /**
     * 按类型统计买家的收藏条数。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @return 条数
     */
    long countByAccountIdAndType(Long accountId, FavoriteType favoriteType);

    /**
     * 统计买家的全部收藏条数（不区分类型）。
     *
     * @param accountId 买家账号 ID
     * @return 条数
     */
    long countByAccountId(Long accountId);

    /**
     * 按买家 + 类型 + 目标删除收藏条目（取消收藏；不存在删 0 行，幂等）。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型
     * @param targetId     收藏目标 ID
     * @return 删除的条数（0 或 1）
     */
    int deleteByAccountIdAndTypeAndTargetId(Long accountId, FavoriteType favoriteType, Long targetId);
}