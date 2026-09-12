package com.nona.inf.events;

import com.nona.domain.logistics.ports.WaybillDelivered;
import com.nona.domain.logistics.ports.WaybillDeliveredPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 签收事件发布端口落地：经 Spring 应用事件发布器在模拟推进器事务内
 * 发布领域事件——事件类型为纯领域对象（POJO），AFTER_COMMIT 投递语义
 * 由监听侧 {@link WaybillDeliveredLogListener} /
 * {@link WaybillDeliveredReceiptListener} 的事务同步监听表达（推进事务
 * 提交后监听器才收到）。
 * <p>
 * 发布失败语义：发布调用异常在推进事务内原样透传（该条推进事务整体
 * 回滚——「发布失败不影响主链路」是监听侧容错承诺，本端口调用面失败
 * 不静默）；事件为日志 + 订单自动完成联动消费（可靠性由同库事务
 * 与 CDC 变更外发承担，事件总线不承担可靠性职责）。
 *
 * @author nona9961
 */
@Component
public class SpringWaybillDeliveredPublisher implements WaybillDeliveredPublisher {

    /**
     * Spring 应用事件发布器（进程内事件分发入口）
     */
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 构造发布器。
     *
     * @param applicationEventPublisher 应用事件发布器
     */
    public SpringWaybillDeliveredPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 原样转发到应用事件发布器（发布本身不落库；事务提交后的投递时
     * 序由监听侧事务同步表达）。
     */
    @Override
    public void publishWaybillDelivered(WaybillDelivered event) {
        applicationEventPublisher.publishEvent(event);
    }
}