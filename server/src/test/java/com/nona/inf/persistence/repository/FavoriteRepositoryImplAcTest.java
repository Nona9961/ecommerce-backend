package com.nona.inf.persistence.repository;

import com.nona.domain.identity.entity.FavoriteEntry;
import com.nona.domain.identity.entity.FavoriteType;
import com.nona.inf.persistence.repository.jpa.FavoriteJpaRepository;
import com.nona.util.IDUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏仓储实现测试（FavoriteRepositoryImpl，infra 实现类单元测试）。
 * <p>
 * 覆盖：保存正常路径（返回 true 且可查回）、唯一约束冲突兜底路径
 * （同买家 + 同类型 + 同目标二次保存返回 false 而非抛异常——并发重复
 * 收藏的幂等第三层防线，用例层事务环境下验证）。
 * 事务环境与用例层一致（@Transactional）：仓库捕获路径必须在事务内
 * 真实生效，而非依赖提交阶段才暴露冲突。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@Transactional
class FavoriteRepositoryImplAcTest {

    /**
     * 被测仓储
     */
    @Autowired
    private FavoriteRepositoryImpl favoriteRepositoryImpl;

    /**
     * JPA 仓储（每用例前清空收藏表）
     */
    @Autowired
    private FavoriteJpaRepository jpaRepository;

    /**
     * 每用例前清空收藏表。
     */
    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
    }

    /**
     * happy：保存新条目 → 返回 true，且按「买家 + 类型 + 目标」可查回。
     */
    @Test
    void save_newEntry_persistsAndReturnsTrue() {
        final FavoriteEntry entry = new FavoriteEntry(IDUtils.generateID(), 1L, FavoriteType.PRODUCT, 100L, null);

        assertThat(favoriteRepositoryImpl.save(entry)).isTrue();
        assertThat(favoriteRepositoryImpl.findByAccountIdAndTypeAndTargetId(1L, FavoriteType.PRODUCT, 100L))
                .isPresent();
    }

    /**
     * critical：同一买家 + 同类型 + 同目标二次保存（并发重复收藏场景，
     * 绕过查重）→ 唯一约束冲突应被仓储捕获并返回 false（视为已存在），
     * 不抛异常不覆盖；收藏表仍只有一行。
     */
    @Test
    void save_duplicateKey_returnsFalse() {
        final FavoriteEntry first = new FavoriteEntry(IDUtils.generateID(), 1L, FavoriteType.PRODUCT, 100L, null);
        assertThat(favoriteRepositoryImpl.save(first)).isTrue();

        final FavoriteEntry duplicate = new FavoriteEntry(IDUtils.generateID(), 1L, FavoriteType.PRODUCT, 100L, null);
        assertThat(favoriteRepositoryImpl.save(duplicate)).isFalse();
        assertThat(favoriteRepositoryImpl.countByAccountId(1L)).isEqualTo(1);
    }
}