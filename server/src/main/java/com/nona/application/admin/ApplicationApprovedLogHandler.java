package com.nona.application.admin;

import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.events.AbstractHandler;
import com.nona.events.Event;
import lombok.extern.slf4j.Slf4j;

/**
 * 审核通过事件的日志兜底处理器：开店编排已由用例层同事务实现
 * （{@link OnboardingReviewUseCase#approve}），事件为通知旁路——本处理器
 * 仅记录事件载荷，便于链路追踪与回归参考（事件总线分发要求事件类型
 * 必须有处理器）。
 *
 * @author nona9961
 */
@Slf4j
public class ApplicationApprovedLogHandler
        extends AbstractHandler<ApplicationApprovedEvent.ApplicationApprovedData, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 仅记录事件载荷（申请 ID / 提交实体 / 店铺名），不承载业务副作用。
     */
    @Override
    public Void handle(Event<ApplicationApprovedEvent.ApplicationApprovedData> event) {
        final ApplicationApprovedEvent.ApplicationApprovedData data = event.getPayload();
        log.info("[onboarding] application approved: applicationId={} accountId={} shopName={}",
                data.applicationId(), data.accountId(), data.shopName());
        return null;
    }
}