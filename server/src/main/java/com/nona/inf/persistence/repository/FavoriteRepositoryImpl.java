package com.nona.inf.persistence.repository;

import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.domain.identity.repo.FavoriteRepository;
import com.nona.inf.persistence.converters.FavoriteConvertor;
import com.nona.inf.persistence.po.identity.FavoritePO;
import com.nona.inf.persistence.repository.jpa.FavoriteJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 收藏仓储落地：直接委托 JPA 仓储操作 single favorite 表行，
 * 实现域仓储契约 {@link FavoriteRepository}。
 * <p>
 * 收藏只有增删查（无属性编辑），不经 DifferRepository 变更追踪——删除语义
 * 模板未实现，且条目注册快照无收益；与账号-店铺关联的「聚合内实体独立
 * 持久化」模式同构。并发兜底：插入撞唯一约束（重复收藏竞态）时捕获
 * 数据完整性冲突返回 false，幂等语义由用例层保持。
 * 分页排序固定收藏时间倒序（最近收藏在前）；审计时间戳由 JPA auditing 填充。
 *
 * @author nona9961
 */
@Component
public class FavoriteRepositoryImpl implements FavoriteRepository {

    /**
     * 收藏 JPA 仓储
     */
    private final FavoriteJpaRepository jpaRepository;

    /**
     * 收藏条目 ↔ PO 转换器
     */
    private final FavoriteConvertor convertor;

    /**
     * 构造仓储实现。
     *
     * @param jpaRepository 收藏 JPA 仓储
     * @param convertor     收藏条目 ↔ PO 转换器
     */
    public FavoriteRepositoryImpl(FavoriteJpaRepository jpaRepository, FavoriteConvertor convertor) {
        this.jpaRepository = jpaRepository;
        this.convertor = convertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FavoriteEntry getByID(Long id) {
        return jpaRepository.findById(id)
                .map(po -> convertor.convertToRoot(po, null))
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 收藏条目插入；并发重复（唯一约束冲突）捕获后返回 false——幂等语义由调用方持有
     * （视为已存在，不抛错不覆盖）。save 后立即 flush 使冲突在调用栈内抛出（若延迟到
     * 用例层事务提交时才 flush，冲突异常将越过本捕获点，幂等兜底即失效）。
     */
    @Override
    public boolean save(FavoriteEntry entry) {
        try {
            jpaRepository.saveAndFlush(convertor.convertToPO(entry));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按条目删除（存在才删，返回删除条数）。取消收藏走
     * {@link #deleteByAccountIdAndTypeAndTargetId} 谓词删除（幂等）。
     */
    @Override
    public int delete(FavoriteEntry entry) {
        return deleteByID(entry.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int deleteByID(Long id) {
        final Optional<FavoritePO> existing = jpaRepository.findById(id);
        if (existing.isEmpty()) {
            return 0;
        }
        jpaRepository.delete(existing.get());
        return 1;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 查重路径直接委托 JPA 定向查询（联合唯一约束保证至多一行）。
     */
    @Override
    public Optional<FavoriteEntry> findByAccountIdAndTypeAndTargetId(Long accountId, FavoriteType favoriteType, Long targetId) {
        return jpaRepository.findByAccountIdAndFavoriteTypeAndTargetId(accountId, favoriteType, targetId)
                .map(po -> convertor.convertToRoot(po, null));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 偏移量换算页码（offset 为行偏移，page = offset / limit），排序固定收藏时间倒序。
     */
    @Override
    public List<FavoriteEntry> listByAccountIdAndType(Long accountId, FavoriteType favoriteType, int offset, int limit) {
        final Page<FavoritePO> page = jpaRepository.findByAccountIdAndFavoriteType(
                accountId, favoriteType, pageRequest(offset, limit));
        return page.getContent().stream().map(po -> convertor.convertToRoot(po, null)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<FavoriteEntry> listByAccountId(Long accountId, int offset, int limit) {
        final Page<FavoritePO> page = jpaRepository.findByAccountId(accountId, pageRequest(offset, limit));
        return page.getContent().stream().map(po -> convertor.convertToRoot(po, null)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByAccountIdAndType(Long accountId, FavoriteType favoriteType) {
        return jpaRepository.countByAccountIdAndFavoriteType(accountId, favoriteType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countByAccountId(Long accountId) {
        return jpaRepository.countByAccountId(accountId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int deleteByAccountIdAndTypeAndTargetId(Long accountId, FavoriteType favoriteType, Long targetId) {
        return Math.toIntExact(jpaRepository.deleteByAccountIdAndFavoriteTypeAndTargetId(accountId, favoriteType, targetId));
    }

    /**
     * 组装分页请求（收藏时间倒序，最近收藏在前）。
     *
     * @param offset 首条偏移量
     * @param limit  每页条数
     * @return 分页请求
     */
    private static PageRequest pageRequest(int offset, int limit) {
        return PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.DESC, "createTime"));
    }
}