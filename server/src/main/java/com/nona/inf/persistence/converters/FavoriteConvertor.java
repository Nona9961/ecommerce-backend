package com.nona.inf.persistence.converters;

import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.inf.persistence.po.identity.FavoritePO;
import org.springframework.stereotype.Component;

/**
 * 收藏条目 ↔ 收藏 PO 转换器（favorite 表字段一一对应，无子对象，不需要 other 辅助参数）。
 *
 * @author nona9961
 */
@Component
public class FavoriteConvertor extends AbstractConvertor<FavoriteEntry, FavoritePO, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 审计时间戳（createTime/updateTime）由 JPA auditing 在持久化时填充，
     * 转换不负责；收藏时间由读取侧从 PO 回填进领域条目。
     */
    @Override
    protected FavoritePO safedConvertToPO(FavoriteEntry root) {
        final FavoritePO po = new FavoritePO();
        po.setId(root.getId());
        po.setAccountId(root.getAccountId());
        po.setFavoriteType(root.getFavoriteType());
        po.setTargetId(root.getTargetId());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FavoriteEntry safedConvertToRoot(FavoritePO po, Void other) {
        return new FavoriteEntry(po.getId(), po.getAccountId(), po.getFavoriteType(), po.getTargetId(), po.getCreateTime());
    }
}