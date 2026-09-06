package com.nona.inf.timeout;

/**
 * 超时处理器注册表：按超时类型路由处理器（注册机制契约）。
 *
 * @see DefaultTimeoutHandlerRegistry
 */
public interface TimeoutHandlerRegistry {

    /**
     * 按类型取处理器。
     *
     * @param type 超时类型
     * @param <T>  业务对象引用类型（由调用方按目标处理器声明）
     * @return 该类型注册的处理器
     * @throws java.lang.IllegalStateException 类型未注册（装配缺失，fail-fast）
     */
    <T> TimeoutHandler<T> get(TimeoutType type);
}