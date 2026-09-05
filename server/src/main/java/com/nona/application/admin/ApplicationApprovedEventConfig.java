package com.nona.application.admin;

import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.events.Dispatcher;
import org.springframework.context.annotation.Configuration;

/**
 * 领域事件注册配置：为身份域事件绑定进程内处理器。
 * <p>
 * 开店编排已由用例层同事务实现（{@link OnboardingReviewUseCase#approve}：
 * 审核通过即建店 + 关联绑定），事件为通知旁路——日志兜底处理器保留作
 * 链路追踪（事件总线分发要求事件类型必须有处理器）。
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