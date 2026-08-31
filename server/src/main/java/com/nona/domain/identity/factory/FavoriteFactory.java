package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

/**
 * 收藏工厂：Favorite 聚合条目的创建与不变量初始化。
 * <p>
 * 条目归属与引用不变量在此保证：买家账号 / 类型 / 目标 ID 任一为空即拒绝
 * （防御校验，契约层已先行拦截）；「同一买家 + 同类型 + 同目标唯一」的幂等
 * 不变量由用例层查重 + 数据库联合唯一约束共同承载（工厂只保证单条形态合法）。
 *
 * @author nona9961
 */
@Component
public class FavoriteFactory {

    /**
     * 创建收藏条目（商品或店铺）。
     *
     * @param accountId    买家账号 ID
     * @param favoriteType 收藏目标类型（PRODUCT / SHOP）
     * @param targetId     收藏目标 ID（引用 ID）
     * @return 新建的收藏条目（收藏时间由持久化层回填）
     */
    public FavoriteEntry createEntry(Long accountId, FavoriteType favoriteType, Long targetId) {
        BusinessAssert.assertNonNull(accountId, "账号 ID 不能为空");
        BusinessAssert.assertNonNull(favoriteType, "收藏类型不能为空");
        BusinessAssert.assertNonNull(targetId, "收藏目标 ID 不能为空");
        return new FavoriteEntry(IDUtils.generateID(), accountId, favoriteType, targetId, null);
    }
}