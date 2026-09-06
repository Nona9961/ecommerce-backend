package com.nona.inf.timeout;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring 收集的处理器注册表默认实现：启动时收集容器中全部
 * 处理器 bean 并按类型建索引；同一类型出现多个实现属于装配错误，
 * 拒绝启动（fail-fast，消除处理歧义）。
 * <p>
 * 类型安全：索引以 {@link TimeoutType} 为键、处理器泛型参数用通配
 * 承载；路由时由调用方以目标类型参数化，注册与取值两侧的类型一致
 * 由数据端口与处理器的成对注册保证。
 */
@Component
public class DefaultTimeoutHandlerRegistry implements TimeoutHandlerRegistry {

    /**
     * 类型 → 处理器索引（构造时构建，不可变）。
     */
    private final Map<TimeoutType, TimeoutHandler<?>> handlers;

    /**
     * @param candidates 容器收集的全部处理器 bean（Spring 注入）；
     *                   构造时按类型建索引，重复类型拒绝（fail-fast）
     */
    public DefaultTimeoutHandlerRegistry(List<TimeoutHandler<?>> candidates) {
        Map<TimeoutType, TimeoutHandler<?>> index = new HashMap<>();
        for (TimeoutHandler<?> candidate : candidates) {
            TimeoutType type = candidate.type();
            if (index.putIfAbsent(type, candidate) != null) {
                throw new IllegalStateException("超时类型重复注册处理器: " + type);
            }
        }
        this.handlers = Map.copyOf(index);
    }

    /**
     * {@inheritDoc}
     */
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