package com.nona.events;

import java.util.concurrent.CompletableFuture;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 分发器
 *
 * @author nona
 */
@ScaffoldGenerated
public interface Dispatcher {

    /**
     * 注册事件处理器。
     *
     * @param eventType 事件类型，不能为空
     * @param handler   事件处理器，不能为 null
     * @param <E>       事件携带的数据类型
     * @param <R>       处理结果类型
     */
    <E, R> void register(String eventType, EventHandler<E, R> handler);

    /**
     * 同步分发事件。
     *
     * @param event 事件
     * @return 处理结果
     * @param <E> 事件携带的数据类型
     * @param <R> 处理结果类型
     */
    <E, R> R dispatch(Event<E> event);

    /**
     * 异步分发事件。
     *
     * @param event 事件
     * @return 异步处理结果
     * @param <E> 事件携带的数据类型
     * @param <R> 处理结果类型
     */
    <E, R> CompletableFuture<R> dispatchAsync(Event<E> event);
}
