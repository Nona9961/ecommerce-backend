package com.nona.application.mall;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.FavoriteItem;
import com.nona.api.mall.FavoriteRequest;
import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.domain.identity.factory.FavoriteFactory;
import com.nona.domain.identity.repo.FavoriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收藏用例（买家端）：收藏 / 取消收藏 / 收藏列表编排。
 * <p>
 * 幂等语义：收藏前按「买家 + 类型 + 目标」查重，已存在即
 * 直接返回（不重复生成）；并发重复由仓储插入 + 唯一约束兜底（冲突视为已存在）。
 * 取消收藏按同一谓词删除，不存在删 0 行同样成功。
 * 当前买家身份由调用方（web 层）从认证上下文传入，不来自请求体；
 * 收藏目标为引用 ID，存在性校验属于目录域（商品/店铺实体），本域不校验。
 * 事务边界：收藏为单行插入，原子性由数据库保证，不设用例事务——并发冲突时
 * 仓储在自身事务内捕获唯一约束冲突视为已存在；若置于用例事务内，冲突会把
 * 事务标记为回滚，提交即失败（幂等兜底失效）。取消收藏为单谓词删除，
 * 保持用例事务（删除语义与未来扩展的编排边界）。读方法直接查询，不建领域根。
 *
 * @author nona9961
 */
@Service
public class FavoriteUseCase {

    /**
     * 收藏仓储
     */
    private final FavoriteRepository favoriteRepository;

    /**
     * 收藏工厂
     */
    private final FavoriteFactory favoriteFactory;

    /**
     * 构造收藏用例。
     *
     * @param favoriteRepository 收藏仓储
     * @param favoriteFactory    收藏工厂
     */
    public FavoriteUseCase(FavoriteRepository favoriteRepository, FavoriteFactory favoriteFactory) {
        this.favoriteRepository = favoriteRepository;
        this.favoriteFactory = favoriteFactory;
    }

    /**
     * 收藏（商品或店铺）：已收藏则幂等返回（不重复生成）。
     * <p>
     * 单行插入不设用例事务（见类注释的事务边界说明）；查重与插入之间的
     * 并发窗口由仓储唯一约束兜底补齐（仓储自身事务内捕获冲突，返回幂等）。
     *
     * @param accountId 当前买家账号 ID（认证上下文）
     * @param request   收藏请求（目标类型 + 目标 ID）
     */
    public void favorite(Long accountId, FavoriteRequest request) {
        final FavoriteType type = toDomainType(request.targetType());
        if (favoriteRepository.findByAccountIdAndTypeAndTargetId(accountId, type, request.targetId()).isPresent()) {
            return;
        }
        favoriteRepository.save(favoriteFactory.createEntry(accountId, type, request.targetId()));
    }

    /**
     * 取消收藏（商品或店铺）：不存在该收藏则幂等返回（不报错）。
     *
     * @param accountId 当前买家账号 ID（认证上下文）
     * @param request   收藏请求（目标类型 + 目标 ID）
     */
    @Transactional
    public void unfavorite(Long accountId, FavoriteRequest request) {
        favoriteRepository.deleteByAccountIdAndTypeAndTargetId(
                accountId, toDomainType(request.targetType()), request.targetId());
    }

    /**
     * 收藏列表（分页，收藏时间倒序；按类型过滤可选）。
     *
     * @param accountId  当前买家账号 ID（认证上下文）
     * @param targetType 收藏目标类型；null 表示全部类型
     * @param query      分页请求
     * @return 分页收藏条目
     */
    public PageResult<FavoriteItem> list(Long accountId, com.nona.api.mall.FavoriteType targetType, PageQuery query) {
        final int offset = Math.toIntExact(query.offset());
        final int limit = query.pageSize();
        final long total;
        final List<FavoriteEntry> entries;
        if (targetType == null) {
            total = favoriteRepository.countByAccountId(accountId);
            entries = favoriteRepository.listByAccountId(accountId, offset, limit);
        } else {
            final FavoriteType type = toDomainType(targetType);
            total = favoriteRepository.countByAccountIdAndType(accountId, type);
            entries = favoriteRepository.listByAccountIdAndType(accountId, type, offset, limit);
        }
        return PageResult.of(entries.stream().map(this::toItem).toList(), total, query);
    }

    /**
     * 领域条目 → API 条目（幂等校验后的条目形态），收藏时间按 ISO 8601 字符串透出。
     *
     * @param entry 领域收藏条目
     * @return API 收藏条目
     */
    private FavoriteItem toItem(FavoriteEntry entry) {
        return new FavoriteItem(entry.getId(), toApiType(entry.getFavoriteType()), entry.getTargetId(),
                entry.getCreateTime().toString());
    }

    /**
     * 契约类型 → 领域类型映射（值一一对应；契约演进时映射点唯一）。
     *
     * @param type 契约收藏类型
     * @return 领域收藏类型
     */
    private static FavoriteType toDomainType(com.nona.api.mall.FavoriteType type) {
        return switch (type) {
            case PRODUCT -> FavoriteType.PRODUCT;
            case SHOP -> FavoriteType.SHOP;
        };
    }

    /**
     * 领域类型 → 契约类型映射（列表响应组装）。
     *
     * @param type 领域收藏类型
     * @return 契约收藏类型
     */
    private static com.nona.api.mall.FavoriteType toApiType(FavoriteType type) {
        return switch (type) {
            case PRODUCT -> com.nona.api.mall.FavoriteType.PRODUCT;
            case SHOP -> com.nona.api.mall.FavoriteType.SHOP;
        };
    }
}