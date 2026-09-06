package com.nona.inf.timeout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 超时调度引擎：定时扫描到期候选并驱动逐条处理。
 * <p>
 * 每 30 秒触发一轮扫描：按数据端口（每超时类型一个）取到期候选，
 * 逐条交给 {@link TimeoutTaskProcessor} 以独立事务处理；单条失败由
 * 该条事务回滚（候选保留，下轮重扫再认领），记录告警后不中断本轮
 * 其余候选。
 * <p>
 * 引擎为纯调度器：不感知业务表形态与业务状态（封装于数据端口），
 * 不感知处理动作（处理器实现内协调业务用例）。时间源注入化
 * （默认系统 UTC 时钟），定时间隔可经配置覆盖（默认 30 秒）。
 * <p>
 * 装配提示：调度注解需装配方开启 Spring 调度能力（@EnableScheduling）。
 */
@Slf4j
@Component
public class TimeoutScheduler {

    /**
     * 单端口单轮扫描上限（LIMIT，防止单轮堆积拖长处理窗口）。
     */
    public static final int SCAN_LIMIT = 100;

    /**
     * 默认扫描间隔（毫秒）：30 秒。
     */
    public static final long SCAN_INTERVAL_MS = 30_000L;

    /**
     * 全部超时类型的数据端口（Spring 按容器收集注入）。
     */
    private final List<TimeoutTaskStore<?>> stores;

    /**
     * 单条候选同事务处理器。
     */
    private final TimeoutTaskProcessor processor;

    /**
     * 扫描时间源。
     */
    private final Clock clock;

    /**
     * Spring 装配构造（容器收集全部数据端口与处理器；时钟默认系统 UTC）。
     *
     * @param stores    全部数据端口
     * @param processor 单条候选处理器
     */
    @Autowired
    public TimeoutScheduler(List<TimeoutTaskStore<?>> stores, TimeoutTaskProcessor processor) {
        this(stores, processor, Clock.systemUTC());
    }

    /**
     * 定制时钟构造（测试注入固定时钟验证扫描时间语义）。
     *
     * @param stores    全部数据端口
     * @param processor 单条候选处理器
     * @param clock     扫描时间源
     */
    TimeoutScheduler(List<TimeoutTaskStore<?>> stores, TimeoutTaskProcessor processor, Clock clock) {
        this.stores = stores;
        this.processor = processor;
        this.clock = clock;
    }

    /**
     * 定时扫描入口（fixedDelay：上轮结束后 30 秒，间隔可经
     * {@code nona.timeout.scan-interval-ms} 配置覆盖）。
     * <p>
     * 单轮流程：取各端口到期候选（每端口 LIMIT 上限）→ 逐条同事务
     * 处理；单条异常记录告警后继续（该条事务已回滚，下轮重扫再认领）。
     */
    @Scheduled(fixedDelayString = "${nona.timeout.scan-interval-ms:30000}")
    public void scan() {
        Instant now = clock.instant();
        for (TimeoutTaskStore<?> store : stores) {
            processDueTasks(store, now);
        }
    }

    /**
     * 处理单个端口的全部到期候选（捕获辅助：保有数据端口与候选的
     * 同源类型参数，逐条同事务处理，单条异常告警后继续）。
     *
     * @param store 数据端口
     * @param now   本轮扫描时刻
     * @param <T>   业务对象引用类型
     */
    private <T> void processDueTasks(TimeoutTaskStore<T> store, Instant now) {
        List<TimeoutTask<T>> due = store.findDue(now, SCAN_LIMIT);
        for (TimeoutTask<T> task : due) {
            try {
                processor.processOne(store, task);
            } catch (RuntimeException e) {
                log.warn("[timeout] task processing failed, will be retried next scan: type={}, taskId={}",
                        store.type(), task.id(), e);
            }
        }
    }
}