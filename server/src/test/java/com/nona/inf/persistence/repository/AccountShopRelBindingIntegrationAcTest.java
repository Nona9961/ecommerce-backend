package com.nona.inf.persistence.repository;

import com.nona.domain.identity.entity.AccountShopRel;
import com.nona.domain.identity.repo.AccountShopRelRepository;
import com.nona.inf.persistence.repository.jpa.AccountShopRelJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号-店铺关联绑定方法集成测试：绑定创建 + 幂等语义 + 并发唯一约束兜底。
 * <p>
 * 绑定语义与收藏等聚合内关联实体一致：已存在同账号同店铺关联时幂等（不重复
 * 创建）；并发重复由 (account_id, shop_id) 联合唯一约束兜底（冲突视为已存在）；
 * 绑定后经 {@link AccountShopRelRepository#findByAccountId} 立即可见。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class AccountShopRelBindingIntegrationAcTest {

    /**
     * 账号-店铺关联仓储（被测对象：绑定方法）
     */
    @Autowired
    private AccountShopRelRepository accountShopRelRepository;

    /**
     * 关联表 JPA 仓储（断言行数与清理）
     */
    @Autowired
    private AccountShopRelJpaRepository accountShopRelJpaRepository;

    /**
     * 每用例前清空关联表。
     */
    @BeforeEach
    void setUp() {
        accountShopRelJpaRepository.deleteAll();
    }

    /**
     * happy：绑定创建后经 findByAccountId 立即可见（验收锚点）。
     */
    @Test
    @DisplayName("绑定创建后按账号可见")
    void bind_createsRel_visibleByFindByAccountId() {
        assertThat(accountShopRelRepository.bind(1001L, 2001L)).isTrue();

        final List<AccountShopRel> rels = accountShopRelRepository.findByAccountId(1001L);
        assertThat(rels).hasSize(1);
        assertThat(rels.getFirst().getAccountId()).isEqualTo(1001L);
        assertThat(rels.getFirst().getShopId()).isEqualTo(2001L);
    }

    /**
     * critical：同一账号同一店铺重复绑定 → 幂等（第二次不产生新行，仅一行）。
     */
    @Test
    @DisplayName("重复绑定幂等不新增行")
    void bind_duplicate_isIdempotent() {
        assertThat(accountShopRelRepository.bind(1001L, 2001L)).isTrue();
        assertThat(accountShopRelRepository.bind(1001L, 2001L)).isFalse();

        assertThat(accountShopRelRepository.findByAccountId(1001L)).hasSize(1);
        assertThat(accountShopRelJpaRepository.count()).isEqualTo(1);
    }

    /**
     * happy：同一账号绑定多个店铺 → 每店铺一行（多店铺同轨：同一账号可关联多个店铺，
     * 后续多店铺切换沿用同一关联）。
     */
    @Test
    @DisplayName("同一账号绑定多店铺各一行")
    void bind_multipleShops_eachOneRow() {
        assertThat(accountShopRelRepository.bind(1001L, 2001L)).isTrue();
        assertThat(accountShopRelRepository.bind(1001L, 2002L)).isTrue();

        final List<AccountShopRel> rels = accountShopRelRepository.findByAccountId(1001L);
        assertThat(rels).hasSize(2);
        assertThat(rels).extracting(AccountShopRel::getShopId)
                .containsExactlyInAnyOrder(2001L, 2002L);
    }

    /**
     * happy：绑定后按店铺反查归属账号可见（契约演进：搜索写后窗口埋点
     * 按归属账号打标的前置反查）。
     */
    @Test
    @DisplayName("绑定后按店铺反查归属账号")
    void bind_createsRel_visibleByFindByShopId() {
        assertThat(accountShopRelRepository.bind(1001L, 2001L)).isTrue();

        final AccountShopRel rel = accountShopRelRepository.findByShopId(2001L).orElseThrow();
        assertThat(rel.getAccountId()).isEqualTo(1001L);
        assertThat(rel.getShopId()).isEqualTo(2001L);
    }

    /**
     * critical：无关联店铺反查为空（不抛错，调用方按无归属处理）。
     */
    @Test
    @DisplayName("无关联店铺反查为空")
    void findByShopId_noRel_empty() {
        assertThat(accountShopRelRepository.findByShopId(9999L)).isEmpty();
    }

    /**
     * error：并发绑定同一账号同一店铺 → 唯一约束兜底，恰一行落库（幂等语义并发展开）。
     */
    @Test
    @DisplayName("并发绑定同一对恰一行落库")
    void bind_concurrent_samePair_onlyOneRow() throws Exception {
        final int threads = 4;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final AtomicInteger bound = new AtomicInteger();
        final AtomicInteger created = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (accountShopRelRepository.bind(1001L, 2001L)) {
                            created.incrementAndGet();
                        }
                        bound.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(bound.get()).isEqualTo(threads);
        assertThat(created.get()).isEqualTo(1);
        assertThat(accountShopRelJpaRepository.count()).isEqualTo(1);
        assertThat(accountShopRelRepository.findByAccountId(1001L)).hasSize(1);
    }
}
