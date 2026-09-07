package com.nona.inf.timeout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单条候选同事务处理契约（引擎关键架构点，场景以行为编排为准）：
 * <ul>
 *   <li>happy：认领成功 → 执行处理 → 清除截止时间（成功闭环）</li>
 *   <li>critical：认领后处理崩溃 → 整体回滚（认领位复位）→ 重扫再认领 → 成功；
 *       失败路径无死认领行</li>
 *   <li>error：认领失败（他方已认领 / 状态已迁移）→ 不执行、不清除、不抛错</li>
 * </ul>
 * 回滚语义说明：真实场景中认领位复位由单条事务的整体回滚达成（本类
 * 方法带事务边界，Spring 代理下生效）；单元测试以
 * {@link TimeoutTestKit.FakeStore#rollbackClaim} 显式模拟崩溃现场的事务
 * 回滚痕迹，验证「重扫可再认领」恢复闭环。
 */
class TimeoutTaskProcessorUnitTest {

    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    @Test
    @DisplayName("happy：认领成功后执行处理并清除截止时间")
    void processOne_claimsFiresAndClears() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutTaskProcessor processor = new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));
        TimeoutTask<Long> task = store.findDue(NOW, 10).get(0);

        boolean handled = processor.processOne(store, task);

        assertThat(handled).isTrue();
        assertThat(handler.fireCount.get()).isEqualTo(1);
        assertThat(store.claimCalls.get()).isEqualTo(1);
        assertThat(store.clearCalls.get()).isEqualTo(1);
        assertThat(store.clearedCount()).isEqualTo(1);
        assertThat(store.isClaimed(1L)).isFalse();
        assertThat(store.findDue(NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("候选已被他方认领：不执行处理、不清除、返回 false")
    void processOne_alreadyClaimed_skips() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        TimeoutTask<Long> task = store.findDue(NOW, 10).get(0);
        assertThat(store.claim(task)).isTrue(); // 他方（前一轮/并发）已抢先认领

        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutTaskProcessor processor = new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));

        boolean handled = processor.processOne(store, task);

        assertThat(handled).isFalse();
        assertThat(handler.fireCount.get()).isZero();
        assertThat(store.clearCalls.get()).isZero();
    }

    @Test
    @DisplayName("候选扫描后被并发迁移状态（如已支付）：认领失败，不执行不清除")
    void processOne_statusMigratedAfterScan_claimFails() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        TimeoutTask<Long> task = store.findDue(NOW, 10).get(0);
        store.migrate(1L); // 并发路径（如买家已支付并清除截止时间）迁移状态

        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutTaskProcessor processor = new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));

        boolean handled = processor.processOne(store, task);

        assertThat(handled).isFalse();
        assertThat(handler.fireCount.get()).isZero();
        assertThat(store.clearCalls.get()).isZero();
    }

    @Test
    @DisplayName("处理抛异常：不继续清除、异常传播（事务回滚认领位，下轮可重扫）")
    void processOne_handlerThrows_abortsAndPropagates() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_SHIP)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        TimeoutTask<Long> task = store.findDue(NOW, 10).get(0);
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_SHIP)
                .failOn(1L);
        TimeoutTaskProcessor processor = new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));

        assertThatThrownBy(() -> processor.processOne(store, task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟处理崩溃");
        assertThat(handler.fireCount.get()).isZero();
        assertThat(store.clearCalls.get()).isZero();
        assertThat(store.clearedCount()).isZero();
    }

    @Test
    @DisplayName("崩溃恢复闭环：处理崩溃回滚 → 重扫候选仍在 → 再认领成功 → 处理成功 → 清除")
    void processOne_crashThenRescan_recoversAndCompletes() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_RECEIVE)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_RECEIVE)
                .failOn(1L);
        TimeoutTaskProcessor processor = new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));

        // 第一轮：认领成功，处理崩溃 → 整体回滚（模拟事务回滚：认领位复位）
        TimeoutTask<Long> firstRound = store.findDue(NOW, 10).get(0);
        assertThatThrownBy(() -> processor.processOne(store, firstRound))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.isClaimed(1L)).isTrue(); // 未回滚前认领位滞留（暂态）
        store.rollbackClaim(1L);
        assertThat(store.isClaimed(1L)).isFalse();
        assertThat(store.findDue(NOW, 10)).hasSize(1); // 候选保留，可重扫

        // 第二轮重扫：再认领成功 → 处理成功 → 清除截止时间
        handler.clearFailure();
        TimeoutTask<Long> secondRound = store.findDue(NOW, 10).get(0);
        boolean recovered = processor.processOne(store, secondRound);

        assertThat(recovered).isTrue();
        assertThat(handler.fireCount.get()).isEqualTo(1);
        assertThat(store.clearCalls.get()).isEqualTo(1);
        assertThat(store.clearedCount()).isEqualTo(1);
        // 闭环后无死认领行：认领位归零且截止时间已清，不再进入候选
        assertThat(store.claimedCount()).isZero();
        assertThat(store.findDue(NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("失败路径不残留死认领行：崩溃回滚后全表认领位归零")
    void processOne_failure_leavesNoDeadClaimedRows() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(1L, Instant.parse("2026-09-01T00:00:00Z"));
        TimeoutTask<Long> task = store.findDue(NOW, 10).get(0);
        TimeoutTestKit.FakeHandler handler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY)
                .failOn(1L);
        TimeoutTaskProcessor processor = new TimeoutTaskProcessor(new TimeoutTestKit.FakeRegistry(handler));

        assertThatThrownBy(() -> processor.processOne(store, task)).isInstanceOf(IllegalStateException.class);
        store.rollbackClaim(1L);

        assertThat(store.claimedCount()).isZero();
        assertThat(store.findDue(NOW, 10)).hasSize(1);
    }

    @Test
    @DisplayName("候选视图可重复构造：findDue 返回同一行候选（目标引用一致性）")
    void findDue_isStableForSameRow() {
        TimeoutTestKit.FakeStore store = new TimeoutTestKit.FakeStore(TimeoutType.ORDER_PAY)
                .addRow(9L, Instant.parse("2026-09-01T00:00:00Z"));

        List<TimeoutTask<Long>> first = store.findDue(NOW, 10);
        List<TimeoutTask<Long>> second = store.findDue(NOW, 10);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).id()).isEqualTo(9L);
        assertThat(second.get(0).id()).isEqualTo(first.get(0).id());
    }
}