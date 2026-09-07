package com.nona.application.mall;

import com.nona.api.mall.AddCartRequest;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.identity.AccountPO;
import com.nona.inf.persistence.po.order.CartItemPO;
import com.nona.inf.persistence.po.order.CartPO;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.persistence.repository.jpa.CartItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.CartJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 购物车并发测试：同买家并发写经买家行锁（借道账号行 FOR UPDATE）串行化，
 * 验证两类收敛语义——
 * <li>并发加购同一 SKU：两笔请求都成功且数量正确累加（无丢失更新，
 * (buyer,sku) 唯一约束下恰一行）；</li>
 * <li>空车并发加购不同 SKU：两笔请求都成功（根行唯一窗口由锁串行化
 * 消除，不会出现双根行冲突）。</li>
 * 独立 H2 实例（LOCK_TIMEOUT 放宽，避免锁等待窗口触发超时打断串行化
 * 收敛），与既有并发判例（AddressDefaultConcurrencyAcTest、
 * InventoryStockConcurrencyAcTest）同形态。
 *
 * @author nona9961
 */
@SpringBootTest(properties = {
        "management.health.redis.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:cart-concurrency;LOCK_TIMEOUT=30000;DB_CLOSE_DELAY=-1"
})
class CartUseCaseConcurrencyAcTest {

    /**
     * 测试买家账号 ID（lockBuyer 借道账号行必须真实存在）
     */
    private static final long BUYER_ID = 90001L;

    /**
     * 测试商品 ID（在售视图返回）
     */
    private static final long PRODUCT_ID = 2001L;

    /**
     * 测试 SKU A（并发加购目标）
     */
    private static final long SKU_A = 3001L;

    /**
     * 测试 SKU B（并发加购目标）
     */
    private static final long SKU_B = 3002L;

    /**
     * 可售上限（统一放行加购数量）
     */
    private static final int AVAILABLE = 10;

    /**
     * 被测购物车用例（真实 bean，事务与放行代理生效）
     */
    @Autowired
    private CartUseCase cartUseCase;

    /**
     * 购物车主表 JPA（根行断言与清理）
     */
    @Autowired
    private CartJpaRepository cartJpaRepository;

    /**
     * 购物车条目 JPA（子表行断言与清理）
     */
    @Autowired
    private CartItemJpaRepository cartItemJpaRepository;

    /**
     * 账号 JPA（买家账号行准备与清理）
     */
    @Autowired
    private AccountJpaRepository accountJpaRepository;

    /**
     * 商品查询门面桩（跨上下文读替换为可控视图，并发聚焦购物车自身）
     */
    @MockitoBean
    private ProductQueryFacade productQueryFacade;

    /**
     * 每用例前清空购物车两表与账号表，并预置买家账号行与在售视图。
     */
    @BeforeEach
    void setUp() {
        cartItemJpaRepository.deleteAll();
        cartJpaRepository.deleteAll();
        accountJpaRepository.deleteAll();
        final AccountPO account = new AccountPO();
        account.setId(BUYER_ID);
        account.setType(AccountType.BUYER);
        account.setUsername("buyer-" + System.nanoTime());
        account.setPasswordHash("unused");
        account.setStatus(AccountStatus.NORMAL);
        accountJpaRepository.save(account);
        when(productQueryFacade.getBuyerView(anyLong())).thenReturn(viewWith(SKU_A, SKU_B));
    }

    /**
     * critical：同买家并发加购同一 SKU（2 + 3），锁串行化后收敛为恰一行、
     * 数量 = 两笔之和（无并发累加丢失更新）。
     */
    @Test
    @DisplayName("并发加购同一 SKU 累加收敛无丢失")
    void concurrentAddSameSku_accumulatesWithoutLoss() throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger okCount = new AtomicInteger();
        final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            pool.submit(() -> runAdd(start, okCount, SKU_A, 2));
            pool.submit(() -> runAdd(start, okCount, SKU_A, 3));
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(okCount.get()).as("两笔并发加购都应成功（锁等待后串行执行）").isEqualTo(2);
        final CartPO root = cartJpaRepository.findByBuyerId(BUYER_ID).orElseThrow();
        assertThat(root.getId()).isNotNull();
        final List<CartItemPO> rows = cartItemJpaRepository.findByCartIdOrderByIdAsc(root.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSkuId()).isEqualTo(SKU_A);
        assertThat(rows.get(0).getQuantity()).isEqualTo(5);
        assertThat(rows.get(0).getChecked()).isTrue();
    }

    /**
     * critical：空车并发加购不同 SKU：两笔请求都成功，根行恰一行（唯一
     * 窗口由买家锁串行化消除）、条目两行（(buyer,sku) 唯一面各自成立）。
     */
    @Test
    @DisplayName("空车并发加购不同 SKU 收敛单根行双条目")
    void concurrentAddDifferentSkus_onEmptyCart_converges() throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger okCount = new AtomicInteger();
        final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            pool.submit(() -> runAdd(start, okCount, SKU_A, 1));
            pool.submit(() -> runAdd(start, okCount, SKU_B, 1));
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(okCount.get()).as("两笔空车并发加购都应成功（根行窗口由锁消除）").isEqualTo(2);
        final CartPO root = cartJpaRepository.findByBuyerId(BUYER_ID).orElseThrow();
        final List<CartItemPO> rows = cartItemJpaRepository.findByCartIdOrderByIdAsc(root.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CartItemPO::getSkuId).containsExactlyInAnyOrder(SKU_A, SKU_B);
        assertThat(rows).allMatch(row -> row.getQuantity() == 1);
    }

    /**
     * 并发加购任务：起跑等待后在各线程独立追踪作用域内调用用例。
     *
     * @param start   起跑闩
     * @param okCount 成功计数
     * @param skuId   加购 SKU
     * @param qty     加购数量
     */
    private void runAdd(CountDownLatch start, AtomicInteger okCount, Long skuId, int qty) {
        try {
            start.await();
            TrackingContext.withScope(() ->
                    cartUseCase.add(BUYER_ID, new AddCartRequest(PRODUCT_ID, skuId, qty)));
            okCount.incrementAndGet();
        } catch (RuntimeException e) {
            okCount.set(-1);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            okCount.set(-1);
            throw new RuntimeException(e);
        }
    }

    /**
     * 构造买家商品视图（两个可用 SKU 行，可售上限统一放行）。
     *
     * @param skuA SKU A
     * @param skuB SKU B
     * @return 买家视图
     */
    private static ProductBuyerView viewWith(Long skuA, Long skuB) {
        return new ProductBuyerView(PRODUCT_ID, 4001L, "并发测试商品", "并发测试描述",
                List.of(), List.of(), List.of(),
                List.of(new ProductBuyerView.Sku(skuA, "h" + skuA, "规格A", 10000L, AVAILABLE),
                        new ProductBuyerView.Sku(skuB, "h" + skuB, "规格B", 10000L, AVAILABLE)),
                null, new ProductBuyerView.Shop(4001L, "并发店铺", null));
    }
}