package com.nona.application.admin;

import com.nona.domain.identity.ports.ApplicationApprovedEvent;
import com.nona.events.AbstractHandler;
import com.nona.events.Event;
import lombok.extern.slf4j.Slf4j;

/**
 * 审核通过事件的日志兜底处理器：验收期间开店编排消费方未注册时，
 * 事件总线分发不至于因无处理器而失败（dispatch 语义为「必须有处理器」）。
 * <p>
 * 后续版本将以同事务开店处理器替换本处理器（同一事件类型
 * 只保留一个处理器）；本类保留日志行为，便于链路追踪与回归参考。
 *
 * @author nona9961
 */
@Slf4j
public class ApplicationApprovedLogHandler
        extends AbstractHandler<ApplicationApprovedEvent.ApplicationApprovedData, Void> {

    /**
     * {@inheritDoc}
     * <p>
     * 仅记录事件载荷（申请 ID / 提交实体 / 店铺名），不承载业务副作用；
     * 开店编排由后续版本的同事务处理器承担。
     */
    @Override
    public Void handle(Event<ApplicationApprovedEvent.ApplicationApprovedData> event) {
        final ApplicationApprovedEvent.ApplicationApprovedData data = event.getPayload();
        log.info("[onboarding] application approved: applicationId={} accountId={} shopName={}",
                data.applicationId(), data.accountId(), data.shopName());
        return null;
    }
}