package com.nona.inf.events;

import com.nona.events.DefaultDispatcher;
import com.nona.events.Dispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 事件总线装配：将脚手架事件分发器注册为容器 bean。
 * <p>
 * common 模块保持零 Spring 依赖（脚手架原则），{@link DefaultDispatcher}
 * 为纯 Java 类型；装配责任归应用侧，本配置是事件总线的唯一装配点——
 * 新增事件消费方无需再声明 bean，仅需在 {@link #dispatcher()} 返回的
 * 分发器上注册处理器。
 *
 * @author nona9961
 */
@Configuration
public class EventBusConfig {

    /**
     * 提供事件分发器单例 bean。
     *
     * @return 事件分发器（处理器注册表与分发入口）
     */
    @Bean
    public Dispatcher dispatcher() {
        return new DefaultDispatcher();
    }
}