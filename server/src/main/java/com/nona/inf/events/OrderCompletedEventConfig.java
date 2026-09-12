package com.nona.inf.events;

import com.nona.inf.context.RequestContextPropagatingTaskDecorator;
import com.nona.inf.context.TenantContextAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 订单事件异步执行器装配：订单完成事件（确认收货/收货超时）的异步
 * 消费线程池——完成事件监听在订单事务提交后经本执行器异步执行日志
 * 消费，不阻塞请求线程。
 * <p>
 * 上下文传播：执行器显式绑定 {@link RequestContextPropagatingTaskDecorator}
 * （提交线程捕获上下文快照：三元组 + 追踪基线；worker 以
 * {@code withSnapshot + withScope} 双槽嵌套绑定还原执行）——异步消费链路
 * 保持租户视角与追踪基线重建。装饰器不提供自动配置（脚手架约定：每个
 * 异步执行器必须显式接入）。
 *
 * @author nona9961
 */
@Configuration
public class OrderCompletedEventConfig {

    /**
     * 订单事件异步执行器 bean（发布侧监听器消费装配点）。
     *
     * @param tenantContextAccessor 租户上下文访问器（装饰器快照捕获源）
     * @return 订单事件执行器（虚拟线程调度 + 上下文传播装饰器）
     */
    @Bean
    public ThreadPoolTaskExecutor orderEventExecutor(TenantContextAccessor tenantContextAccessor) {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
        executor.setThreadNamePrefix("order-event-");
        executor.setVirtualThreads(true);
        executor.initialize();
        return executor;
    }
}