package com.nona.application.admin;

import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.events.Dispatcher;
import org.springframework.context.annotation.Configuration;

/**
 * 领域事件注册配置：为身份域事件绑定进程内处理器。
 * <p>
 * 当前仅注册审核通过事件的日志兜底处理器（事件总线分发要求事件类型
 * 必须有处理器）；后续版本在同事件类型上以开店编排处理器
 * 替换（同类型只保留一个处理器）。
 *
 * @author nona9961
 */
@Configuration
public class ApplicationApprovedEventConfig {

    /**
     * 构造配置：注册审核通过事件处理器。
     *
     * @param dispatcher 事件分发器
     */
    public ApplicationApprovedEventConfig(Dispatcher dispatcher) {
        dispatcher.register(ApplicationApprovedEvent.TYPE, new ApplicationApprovedLogHandler());
    }
}