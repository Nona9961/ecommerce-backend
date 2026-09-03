package com.nona.inf.events;

import com.nona.inf.context.RequestContextPropagatingTaskDecorator;
import com.nona.inf.context.TenantContextAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 库存事件异步执行器装配：库存领域事件（售罄/恢复）的异步消费线程池
 * ——事件监听在库存事务提交后经本执行器异步执行日志消费，不阻塞请求
 * 线程。
 * <p>
 * 上下文传播：执行器显式绑定 {@link RequestContextPropagatingTaskDecorator}
 * （提交线程捕获请求上下文快照 → worker 结构化作用域绑定执行）——异步
 * 消费链路保持租户视角（fail-closed 语义下无视角的消费无法落租户列）。
 * 装饰器不提供自动配置（脚手架约定：每个异步执行器必须显式接入）。
 *
 * @author nona9961
 */
@Configuration
public class InventoryEventConfig {

    /**
     * 事件异步执行器 bean（监听器与发布侧共用装配点）。
     *
     * @param tenantContextAccessor 租户上下文访问器（装饰器快照捕获源）
     * @return 事件执行器（虚拟线程调度 + 上下文传播装饰器）
     */
    @Bean
    public ThreadPoolTaskExecutor stockEventExecutor(TenantContextAccessor tenantContextAccessor) {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
        executor.setThreadNamePrefix("stock-event-");
        executor.setVirtualThreads(true);
        executor.initialize();
        return executor;
    }
}