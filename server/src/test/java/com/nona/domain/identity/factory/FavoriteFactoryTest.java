package com.nona.domain.identity.factory;

import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收藏工厂单元测试：收藏条目创建与不变量初始化（Favorite 聚合内实体）。
 * <p>
 * 收藏不变量：同一买家 + 同类型 + 同目标唯一（幂等）；条目标识经
 * ID 工具生成，禁止手动赋值；目标 ID 为引用 ID（目标实体属目录域，本域不校验存在性）。
 * 覆盖：happy（商品/店铺条目创建）、error（账号 ID / 类型 / 目标 ID 为空的防御校验）。
 */
class FavoriteFactoryTest {

    /**
     * 被测工厂
     */
    private final FavoriteFactory factory = new FavoriteFactory();

    /**
     * happy：创建商品收藏条目——ID 生成、买家 ID、类型与目标 ID 正确。
     */
    @Test
    void createEntry_product_initializesEntry() {
        final FavoriteEntry entry = factory.createEntry(1001L, FavoriteType.PRODUCT, 2001L);
        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getAccountId()).isEqualTo(1001L);
        assertThat(entry.getFavoriteType()).isEqualTo(FavoriteType.PRODUCT);
        assertThat(entry.getTargetId()).isEqualTo(2001L);
    }

    /**
     * happy：创建店铺收藏条目——类型 SHOP 正确写入。
     */
    @Test
    void createEntry_shop_initializesEntry() {
        final FavoriteEntry entry = factory.createEntry(1001L, FavoriteType.SHOP, 3001L);
        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getAccountId()).isEqualTo(1001L);
        assertThat(entry.getFavoriteType()).isEqualTo(FavoriteType.SHOP);
        assertThat(entry.getTargetId()).isEqualTo(3001L);
    }

    /**
     * error：买家 ID 为空 → 业务拒绝（聚合归属不变量）。
     */
    @Test
    void createEntry_nullAccountId_rejects() {
        assertThatThrownBy(() -> factory.createEntry(null, FavoriteType.PRODUCT, 2001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号");
    }

    /**
     * error：收藏类型为空 → 业务拒绝（类型是条目不变量）。
     */
    @Test
    void createEntry_nullType_rejects() {
        assertThatThrownBy(() -> factory.createEntry(1001L, null, 2001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收藏类型");
    }

    /**
     * error：收藏目标 ID 为空 → 业务拒绝（引用 ID 是条目不变量）。
     */
    @Test
    void createEntry_nullTargetId_rejects() {
        assertThatThrownBy(() -> factory.createEntry(1001L, FavoriteType.PRODUCT, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收藏目标");
    }
}