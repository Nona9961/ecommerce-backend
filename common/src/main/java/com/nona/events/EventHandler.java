package com.nona.events;

import java.util.concurrent.CompletableFuture;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 事件处理接口
 *
 * @param <E> 数据类型
 * @param <R> 返回的结果
 * @author nona
 */
@ScaffoldGenerated
public interface EventHandler<E, R> {

    /**
     * 同步处理事件。
     *
     * @param event 事件
     * @return 处理结果
     */
    R handle(Event<E> event);

    /**
     * 异步处理事件。
     *
     * @param event 事件
     * @return 异步处理结果
     */
    CompletableFuture<R> handleAsync(Event<E> event);
}
