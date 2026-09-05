package com.nona.application.support;

import com.nona.application.support.InventoryReservationUseCase;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.InventoryItemRepositoryImpl;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存并发防超卖测试：N 个线程竞争 M 份可售库存
 * （每线程预占 1 份、订单号唯一），恰好 M 个请求成功、其余全部以容量
 * 不足业务异常拒绝——最终三态一致（可售归零、预占 = M、已售为零、
 * version 逐笔推进（初始化调整 1 笔 + M 笔预占），PREOCCUPY 流水恰好 M 行。
 * <p>
 * 确定性设计（无睡眠依赖）：
 * <ul>
 *     <li>虚拟线程池（Java 25，每任务新建线程，无需清理线程局部残留）；</li>
 *     <li>CountDownLatch 同步起跑——全部请求就绪后同时放行，竞争窗口最大化；</li>
 *     <li>Future#get 等待全部完成（事务在用例方法调用返回前已提交——代理
 *         拦截器内 commit）后才做最终断言，结果收集按异常类型分类计数；</li>
 *     <li>前提冲突失败（聚合前置守卫拒绝）与并发防线失败（条件更新影响
 *         行数 0）均以 INVENTORY_INSUFFICIENT 呈现——两类皆计入不足数，
 *         其余异常计入意外数（意外的非业务异常直接暴露实现缺陷）；</li>
 *     <li>重复轮次（每轮独立清库重建库存）防调度偶发。</li>
 * </ul>
 * 每个工作线程自带请求作用域 + 租户上下文（条件更新的租户条件注入依赖
 * 请求上下文），避免跨线程上下文污染。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryStockConcurrencyTest {

    /**
     * 测试店铺的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT = "9201";

    /**
     * 测试店铺 ID（初始化库存的归属）
     */
    private static final long SHOP = 9201L;

    /**
     * 测试 SKU（并发竞争目标）
     */
    private static final long SKU = 880201L;

    /**
     * 竞争线程数（订单号从该基数起逐一递增，幂等键天然不冲突）
     */
    private static final int THREADS = 64;

    /**
     * 可售库存份数（恰好这一数量的请求成功）
     */
    private static final int STOCK = 10;

    /**
     * 竞争请求订单号基数
     */
    private static final long BASE_ORDER = 770000L;

    /**
     * 并发在测容器内跑满的轮次（每轮独立清库重建库存）
     */
    private static final int ROUNDS = 3;

    /**
     * 被测编排用例（事务边界；每线程独立事务）
     */
    @Autowired
    private InventoryReservationUseCase reservationUseCase;

    /**
     * 库存聚合根仓储（初始库存装配）
     */
    @Autowired
    private InventoryItemRepositoryImpl inventoryItemRepository;

    /**
     * 库存主表 JPA（最终态断言行与清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（流水行数断言与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 库存聚合根工厂（初始库存装配）
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

    /**
     * 编程式事务模板（初始库存装配：工厂建行 + 手工调整补货）
     */
    @Autowired
    private TransactionTemplate tx;


    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 被竞争库存行 ID（每轮初始化后定型）
     */
    private long itemId;

    /**
     * 每轮前：提权清空两表 + 店铺租户作用域（withScope 内事务装配）
     * M 份可售库存。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
        tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(SHOP, SKU);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("concurrency-initializer", STOCK, "并发竞争初始库存");
            inventoryItemRepository.save(loaded);
            itemId = loaded.getId();
            return null;
        });
        });
    }

    /**
     * 并发预占：N 线程 × 1 份 × M 份库存——恰好 M 成功、N−M 容量不足
     * 拒绝，最终三态与流水数精确等于 M（防超卖：任何成功请求都不允许
     * 使可售越界负值）。
     */
    @RepeatedTest(ROUNDS)
    @DisplayName("N线程竞争M份库存恰好M成功")
    void concurrentPreoccupy_exactlyMStockSucceed() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(TENANT);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger insufficient = new AtomicInteger();
        final AtomicInteger unexpected = new AtomicInteger();

        try (final ExecutorService executor =
                     Executors.newThreadPerTaskExecutor(
                             Thread.ofVirtual().name("cas-worker-", 0).factory())) {
            final List<Future<?>> futures = new ArrayList<>(THREADS);
            IntStream.range(0, THREADS).forEach(i -> futures.add(executor.submit((Runnable) () -> {
                        try {
                            startLatch.await();
                            TrackingContext.withScope(() -> {
                                TrackingContext.scope().setTenantID(TENANT);
                                reservationUseCase.preoccupy(BASE_ORDER + i, SKU, 1);
                                success.incrementAndGet();
                            });
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            unexpected.incrementAndGet();
                        } catch (final BusinessException e) {
                            if (EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code().equals(e.getBusinessCode())) {
                                insufficient.incrementAndGet();
                            } else {
                                unexpected.incrementAndGet();
                            }
                        } catch (final Throwable t) {
                            unexpected.incrementAndGet();
                        }
                    })));

            startLatch.countDown();
            for (final Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    unexpected.incrementAndGet();
                } catch (final java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    unexpected.incrementAndGet();
                }
            }
        }

        assertThat(success).hasValue(STOCK);
        assertThat(insufficient).hasValue(THREADS - STOCK);
        assertThat(unexpected).hasValue(0);

        final InventoryItemPO po = inventoryItemJpaRepository.findById(itemId).orElseThrow();
        assertThat(po.getAvailable()).isZero();
        assertThat(po.getHeld()).isEqualTo(STOCK);
        assertThat(po.getSold()).isZero();
        assertThat(po.getVersion()).isEqualTo(STOCK + 1);

        final List<InventoryLogPO> preoccupyLogs = inventoryLogJpaRepository
                .findBySkuIdOrderByIdDesc(SKU, PageRequest.of(0, 100))
                .stream()
                .filter(log -> log.getType() == InventoryLogType.PREOCCUPY)
                .toList();
        assertThat(preoccupyLogs).hasSize(STOCK);
        });
    }
}