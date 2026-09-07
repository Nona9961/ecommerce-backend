package com.nona.inf.timeout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 调度引擎扫描契约（批次循环 + 单条同事务处理 + 时间边界）：
 * <ul>
 *   <li>happy：到期候选经扫描被认领、处理、清除；多类型按端口路由；LIMIT 传递</li>
 *   <li>critical：截止时间恰达扫描时刻（&lt;= 边界）即处理；未到期不处理；
 *       单条处理崩溃不中断批次（失败条保留待重扫）</li>
 *   <li>error：无到期候选时空扫描零操作</li>
 * </ul>
 */
class TimeoutSchedulerUnitTest {

    /**
     * 注入时钟固定时刻（扫描时间源可测）。
     */
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    /**
     * 候选截止时间（早于扫描时刻）。
     */
    private static final Instant DUE = Instant.parse("2026-09-01T00:00:00Z");

    private TimeoutScheduler newScheduler(List<TimeoutTaskStore<?>> stores,
                                          TimeoutTestKit.FakeHandler... handlers) {
        TimeoutTaskProcessor processor =
                new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handlers));
        return new TimeoutScheduler(stores, processor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("happy：扫描驱动单候选全链路（认领 → 处理 → 清除）")
    void scan_claimsFiresAndClears_whenDueCandidate() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, DUE);
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutScheduler scheduler = newScheduler(List.of(store), handler);

        scheduler.scan();

        assertThat(handler.fireCount.get()).isEqualTo(1);
        assertThat(store.findDueCalls.get()).isEqualTo(1);
        assertThat(store.claimCalls.get()).isEqualTo(1);
        assertThat(store.clearCalls.get()).isEqualTo(1);
        assertThat(store.clearedCount()).isEqualTo(1);
        assertThat(store.findDue(NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("happy：多类型端口各按类型路由到对应处理器")
    void scan_routesEachTypeToItsHandler() {
        TimeoutTestKit.FakeStore payStore = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, DUE);
        TimeoutTestKit.FakeStore shipStore = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_SHIP)
                .addRow(2L, DUE);
        TimeoutTestKit.FakeHandler payHandler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutTestKit.FakeHandler shipHandler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_SHIP);
        TimeoutScheduler scheduler = newScheduler(List.of(payStore, shipStore), payHandler, shipHandler);

        scheduler.scan();

        assertThat(payHandler.fireCount.get()).isEqualTo(1);
        assertThat(shipHandler.fireCount.get()).isEqualTo(1);
        assertThat(payStore.clearedCount()).isEqualTo(1);
        assertThat(shipStore.clearedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("happy：扫描按引擎上限（LIMIT 100）取候选")
    void scan_usesScanLimit() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, DUE);
        TimeoutScheduler scheduler =
                newScheduler(List.of(store), new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY));

        scheduler.scan();

        assertThat(store.lastLimit).isEqualTo(100);
        assertThat(store.lastLimit).isEqualTo(TimeoutScheduler.SCAN_LIMIT);
    }

    @Test
    @DisplayName("happy：扫描时间源来自注入时钟（固定时钟下行为可测）")
    void scan_usesInjectedClock() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, NOW.plusSeconds(60)); // 注入时刻尚未到期
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutScheduler scheduler = newScheduler(List.of(store), handler);

        scheduler.scan();

        assertThat(store.lastNow).isEqualTo(NOW);
        assertThat(handler.fireCount.get()).isZero();
    }

    @Test
    @DisplayName("critical：截止时间恰等于扫描时刻（<= 边界）即到期处理")
    void scan_deadlineAtNow_isDue() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, NOW);
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutScheduler scheduler = newScheduler(List.of(store), handler);

        scheduler.scan();

        assertThat(handler.fireCount.get()).isEqualTo(1);
        assertThat(store.clearedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("critical：截止时间未到（边界）不被扫描，下轮到期后仍可处理")
    void scan_deadlineNotReached_skipped() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, NOW.plusSeconds(1));
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutScheduler scheduler = newScheduler(List.of(store), handler);

        scheduler.scan();

        assertThat(handler.fireCount.get()).isZero();
        assertThat(store.claimCalls.get()).isZero();
        // 扫描时刻未到期不在候选；到期后仍可被下轮扫描处理
        assertThat(store.findDue(NOW, 10)).isEmpty();
        assertThat(store.findDue(NOW.plusSeconds(2), 10)).hasSize(1);
    }

    @Test
    @DisplayName("critical：单条处理崩溃不中断批次，其余候选照常完成，失败条保留待重扫")
    void scan_singleFailure_doesNotAbortBatch() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_SHIP)
                .addRows(1L, 2L);
        TimeoutTestKit.FakeHandler handler =
                new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_SHIP).failOn(1L);
        TimeoutScheduler scheduler = newScheduler(List.of(store), handler);

        // 单条异常被记录（事务回滚该条），不中断批次、不向外抛出
        assertThatCode(scheduler::scan).doesNotThrowAnyException();

        assertThat(store.claimCalls.get()).isEqualTo(2);   // 两条均尝试认领
        assertThat(handler.fireCount.get()).isEqualTo(1);  // 仅成功条被执行
        assertThat(store.clearCalls.get()).isEqualTo(1);   // 仅成功条被清除
        assertThat(store.clearedCount()).isEqualTo(1);
        // 失败条截止时间未清（认领位由事务回滚复位 → 重扫可再认领，
        // 闭环在单条清理用例中显式验证），本用例验证批次隔离
        assertThat(store.findDue(NOW, 10))
                .extracting(task -> task.id())
                .containsExactly(1L);
    }

    @Test
    @DisplayName("error：无到期候选时空扫描零操作")
    void scan_noDueCandidates_noop() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY);
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutScheduler scheduler = newScheduler(List.of(store), handler);

        scheduler.scan();

        assertThat(store.findDueCalls.get()).isEqualTo(1); // 仅做一次扫描
        assertThat(handler.fireCount.get()).isZero();
        assertThat(store.claimCalls.get()).isZero();
        assertThat(store.clearCalls.get()).isZero();
    }
}