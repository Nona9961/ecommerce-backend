package com.nona.events;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 事件处理的抽象基类：将 {@link #handle(Event)} 包装为虚拟线程上的异步执行。
 * <p>
 * 子类只需实现 {@link #handle(Event)}，异步调度由本类统一提供。
 *
 * @author nona
 */
@ScaffoldGenerated
public abstract class AbstractHandler<E, R> implements EventHandler<E, R> {

    /**
     * 虚拟线程执行器（每个任务一个新虚拟线程）
     */
    private static final Executor executor = buildVirtualExecutor();

    /**
     * 构建基于虚拟线程的执行器。
     *
     * @return 虚拟线程执行器
     */
    private static Executor buildVirtualExecutor() {
        final ThreadFactory factory = Thread.ofVirtual()
                .name("handler-", 0)
                .factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<R> handleAsync(Event<E> event) {
        return CompletableFuture.supplyAsync(() -> handle(event), executor);
    }
}
