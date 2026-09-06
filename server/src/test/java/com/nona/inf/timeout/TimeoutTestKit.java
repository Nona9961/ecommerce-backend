package com.nona.inf.timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 引擎单元测试替身集：以内存行模拟「截止时间列承载于业务表」的
 * 数据端口语义（候选扫描 / 乐观锁认领 / 截止时间清除 / 并发状态迁移 /
 * 崩溃回滚痕迹），以及可编排失败与计数的处理器、可编程路由的注册表。
 * <p>
 * 回滚语义说明：真实回滚由单条事务（回滚认领位）保证，单元测试
 * 显式调用 {@link FakeStore#rollbackClaim(Long)} 模拟崩溃/异常现场的
 * 事务回滚痕迹，验证「重扫可再认领」恢复闭环。
 */
final class TimeoutTestKit {

    private TimeoutTestKit() {
    }

    /**
     * 内存数据端口：内部行模拟表行（status 是否仍为预期态 / claimed /
     * timeout_at 三要素），findDue 与 claim 复刻条件 SQL 语义。
     */
    static final class FakeStore implements TimeoutTaskStore<Long> {

        private final TimeoutType type;
        private final List<Row> rows = new ArrayList<>();
        final AtomicInteger findDueCalls = new AtomicInteger();
        final AtomicInteger claimCalls = new AtomicInteger();
        final AtomicInteger clearCalls = new AtomicInteger();
        Integer lastLimit;
        Instant lastNow;

        FakeStore(TimeoutType type) {
            this.type = type;
        }

        /**
         * 造一行到期候选（status 处于预期态）。
         *
         * @param id        业务主行主键
         * @param timeoutAt 截止时间（是否到期由 findDue 按 now 过滤）
         */
        FakeStore addRow(long id, Instant timeoutAt) {
            rows.add(new Row(id, timeoutAt, true, false));
            return this;
        }

        /**
         * 批量造行。
         */
        FakeStore addRows(long... ids) {
            for (long id : ids) {
                addRow(id, Instant.parse("2026-09-01T00:00:00Z"));
            }
            return this;
        }

        /**
         * 模拟并发路径迁移状态（如买家主动支付/取消已同步清除截止时间）：
         * 该行 status 离开预期态，后续 findDue 不再返回、claim 条件不满足。
         */
        synchronized void migrate(long id) {
            row(id).statusOpen = false;
        }

        /**
         * 模拟事务回滚：该候选认领位复位（崩溃/异常路径由整体回滚达成，
         * 测试显式模拟后即可验证重扫再认领闭环）。
         */
        synchronized void rollbackClaim(long id) {
            row(id).claimed = false;
        }

        /**
         * 查询该候选当前认领位。
         */
        synchronized boolean isClaimed(long id) {
            return row(id).claimed;
        }

        /**
         * 已处理完成（截止时间已清除）的行数。
         */
        synchronized int clearedCount() {
            int count = 0;
            for (Row row : rows) {
                if (row.timeoutAt == null) {
                    count++;
                }
            }
            return count;
        }

        /**
         * 当前认领位为 1 的行数（死认领行断言）。
         */
        synchronized int claimedCount() {
            int count = 0;
            for (Row row : rows) {
                if (row.claimed) {
                    count++;
                }
            }
            return count;
        }

        private Row row(long id) {
            for (Row row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            throw new IllegalStateException("无此行: " + id);
        }

        @Override
        public TimeoutType type() {
            return type;
        }

        @Override
        public synchronized List<TimeoutTask<Long>> findDue(Instant now, int limit) {
            findDueCalls.incrementAndGet();
            lastLimit = limit;
            lastNow = now;
            List<TimeoutTask<Long>> due = new ArrayList<>();
            for (Row row : rows) {
                if (row.statusOpen && row.timeoutAt != null && !row.timeoutAt.isAfter(now)) {
                    due.add(new TimeoutTask<>(row.id, row.id));
                }
            }
            return due;
        }

        @Override
        public synchronized boolean claim(TimeoutTask<Long> task) {
            claimCalls.incrementAndGet();
            Row row = rows.stream().filter(r -> Objects.equals(r.id, task.id())).findFirst().orElse(null);
            if (row == null) {
                return false;
            }
            if (row.claimed || !row.statusOpen) {
                return false;
            }
            row.claimed = true;
            return true;
        }

        @Override
        public synchronized void clearDeadline(TimeoutTask<Long> task) {
            clearCalls.incrementAndGet();
            rows.stream().filter(r -> Objects.equals(r.id, task.id())).findFirst()
                    .ifPresent(row -> {
                        row.timeoutAt = null;
                        row.claimed = false;
                    });
        }

        private static final class Row {

            final long id;
            Instant timeoutAt;
            boolean statusOpen;
            boolean claimed;

            Row(long id, Instant timeoutAt, boolean statusOpen, boolean claimed) {
                this.id = id;
                this.timeoutAt = timeoutAt;
                this.statusOpen = statusOpen;
                this.claimed = claimed;
            }
        }
    }

    /**
     * 可编排处理器：统计 fire 次数；可指定「对某 target 抛出异常」
     * （幂等/崩溃场景编排），也可清除失败配置恢复成功路径。
     */
    static final class FakeHandler implements TimeoutHandler<Long> {

        private final TimeoutType type;
        final AtomicInteger fireCount = new AtomicInteger();
        private Long failTarget;
        private long fireDelayMillis;

        FakeHandler(TimeoutType type) {
            this.type = type;
        }

        /**
         * 编排：对该 target 的 fire 抛出运行时异常（模拟处理崩溃）。
         */
        FakeHandler failOn(long target) {
            this.failTarget = target;
            return this;
        }

        /**
         * 清除失败编排（恢复成功路径）。
         */
        FakeHandler clearFailure() {
            this.failTarget = null;
            return this;
        }

        /**
         * 编排：fire 前等待指定毫秒（模拟真实业务处理耗时，等价于 DB
         * 事务处理窗口，用于并发测试稳定制造两个认领请求的重叠）。
         */
        FakeHandler fireDelay(long millis) {
            this.fireDelayMillis = millis;
            return this;
        }

        @Override
        public TimeoutType type() {
            return type;
        }

        @Override
        public void fire(Long target) {
            if (failTarget != null && failTarget == target) {
                throw new IllegalStateException("模拟处理崩溃: target=" + target);
            }
            if (fireDelayMillis > 0) {
                try {
                    Thread.sleep(fireDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("模拟处理被中断", e);
                }
            }
            fireCount.incrementAndGet();
        }
    }

    /**
     * 可编程路由注册表（按类型哈希路由；缺省类型抛明确异常）。
     */
    static final class FakeRegistry implements TimeoutHandlerRegistry {

        private final Map<TimeoutType, TimeoutHandler<?>> handlers = new HashMap<>();

        FakeRegistry(TimeoutHandler<?>... handlers) {
            for (TimeoutHandler<?> handler : handlers) {
                this.handlers.put(handler.type(), handler);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> TimeoutHandler<T> get(TimeoutType type) {
            TimeoutHandler<?> handler = handlers.get(type);
            if (handler == null) {
                throw new IllegalStateException("超时类型未注册处理器: " + type);
            }
            return (TimeoutHandler<T>) handler;
        }
    }
}