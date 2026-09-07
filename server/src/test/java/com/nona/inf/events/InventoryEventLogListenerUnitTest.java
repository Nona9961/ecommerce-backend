package com.nona.inf.events;

import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 库存领域事件日志监听单元测试：{@link TransactionalEventListener}
 * AFTER_COMMIT 订阅的监听方法将日志任务提交到事件执行器——异步消费与
 * 库存主链路解耦（消费失败/提交拒绝均捕获为告警，不向发布侧传播
 * 异常；一期仅日志留痕，不承载业务副作用）。
 *
 * @author nona9961
 */
class InventoryEventLogListenerUnitTest {

    /**
     * 事件异步执行器（mock）
     */
    private final Executor executor = mock(Executor.class);

    /**
     * 被测日志监听（与执行器同实例组装）
     */
    private final InventoryEventLogListener listener = new InventoryEventLogListener(executor);

    /**
     * 每用例前：重置 mock 交互记录（防跨用例污染）。
     */
    @BeforeEach
    void setUp() {
        reset(executor);
    }

    /**
     * happy：售罄事件提交日志任务到执行器（任务捕获事件载荷，异步
     * 执行不阻塞监听线程）。
     */
    @Test
    @DisplayName("售罄事件提交异步日志任务")
    void onSellout_submitsAsyncTask() {
        final SelloutEvent event = new SelloutEvent(88001L);

        assertThatCode(() -> listener.onSellout(event)).doesNotThrowAnyException();

        verify(executor).execute(any(Runnable.class));
    }

    /**
     * happy：恢复事件提交日志任务到执行器。
     */
    @Test
    @DisplayName("恢复事件提交异步日志任务")
    void onRestock_submitsAsyncTask() {
        final RestockEvent event = new RestockEvent(88002L);

        assertThatCode(() -> listener.onRestock(event)).doesNotThrowAnyException();

        verify(executor).execute(any(Runnable.class));
    }

    /**
     * 正常路径：日志任务经执行器立即执行时无异常向监听线程传播（提交与
     * 任务体 fail-fast 为防御语义，见 submit 双层容错）。
     */
    @Test
    @DisplayName("日志任务正常执行不向监听线程传播异常")
    void onSellout_taskRuns_withoutPropagation() {
        final SelloutEvent event = new SelloutEvent(88001L);
        final AtomicInteger executed = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            executed.incrementAndGet();
            return null;
        }).when(executor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onSellout(event)).doesNotThrowAnyException();

        assertThat(executed).hasValue(1);
    }

    /**
     * error：提交被拒绝（执行器饱和/关闭）——监听方法捕获为告警，
     * 不向发布侧传播异常（消费缺失不影响库存主链路）。
     */
    @Test
    @DisplayName("提交被拒绝时异常被捕获不传播")
    void onSellout_submitRejected_swallowed() {
        doThrow(new IllegalStateException("执行器已关闭"))
                .when(executor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onSellout(new SelloutEvent(88001L)))
                .doesNotThrowAnyException();

        verify(executor).execute(any(Runnable.class));
    }
}