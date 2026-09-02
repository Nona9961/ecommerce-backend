/**
 * 同进程事件分发基础设施：{@link Dispatcher} 分发接口、{@link DefaultDispatcher} 默认实现、
 * {@link Event} 事件模型、{@link EventHandler} 处理器接口与 {@link AbstractHandler} 异步处理基类。
 * <p>
 * <b>装配责任</b>：common 模块零 Spring 依赖（脚手架原则），本包不提供组件注册与自动配置；
 * 事件总线的装配由消费方应用侧承担——将 {@link DefaultDispatcher} 注册为 {@link Dispatcher} 即可使用：
 * <pre>{@code
 * @Bean
 * public Dispatcher dispatcher() {
 *     return new DefaultDispatcher();
 * }
 * }</pre>
 * 用法示例：
 * <pre>{@code
 * dispatcher.register("order.created", event -> handle(event.getPayload()));
 * dispatcher.dispatch(event);        // 同步分发
 * dispatcher.dispatchAsync(event);   // 虚拟线程异步分发
 * }</pre>
 *
 * @author nona9961
 */
package com.nona.events;