package com.nona.inf.timeout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调度并发契约：两个调度方并发抢同一到期候选时，乐观锁认领保证
 * 恰一个成功（唯一处理权），另一方认领失败不重复执行。
 */
class TimeoutSchedulerConcurrencyUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    @Test
    @DisplayName("两个调度方并发抢同一候选：仅一个认领成功、仅处理一次")
    void concurrentClaim_singleWinner_singleFire() throws Exception {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        // fire 延迟制造确定性的认领竞争窗口（模拟真实业务处理耗时，等价于
        // DB 事务窗口）：两线程同时出发，后者必在先者清除前到达 claim
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY)
                .fireDelay(100);
        TimeoutTaskProcessor processor =
                new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));
        TimeoutTask<Long> task = store.findDue(NOW, 10).get(0);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Boolean> results;
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return processor.processOne(store, task);
                }));
            }
            go.countDown();
            results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // 恰一个方向认领成功（true），另一方认领失败（false）
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(handler.fireCount.get()).isEqualTo(1);
        assertThat(store.clearCalls.get()).isEqualTo(1);
        assertThat(store.clearedCount()).isEqualTo(1);
        // 处理完成后无认领位残留，候选退出扫描
        assertThat(store.claimedCount()).isZero();
        assertThat(store.findDue(NOW, 10)).isEmpty();
    }
}