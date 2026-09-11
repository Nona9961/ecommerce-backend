package com.nona.inf.events;

import com.nona.domain.order.ports.OrderCompleted;
import com.nona.domain.order.ports.OrderCompletedEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 完成事件发布端口落地：经 Spring 应用事件发布器在完成编排事务内发布
 * 领域事件——事件类型为纯领域对象（POJO），AFTER_COMMIT 投递语义由监听
 * 侧 {@link OrderCompletedLogListener} 的事务同步监听表达（@Transactional
 * 用例方法发布，订单事务提交后监听器才收到）。
 * <p>
 * 发布失败语义：发布调用异常在编排提权事务段内原样透传（同事务整体
 * 回滚——「发布失败不影响主链路」是监听侧容错承诺，本端口调用面失败
 * 不静默）；事件为日志型消费（可靠性由同库事务与 CDC 变更外发
 * 承担，事件总线不承担可靠性职责）。
 *
 * @author nona9961
 */
@Component
public class SpringOrderCompletedEventPublisher implements OrderCompletedEventPublisher {

    /**
     * Spring 应用事件发布器（进程内事件分发入口）
     */
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 构造发布器。
     *
     * @param applicationEventPublisher 应用事件发布器
     */
    public SpringOrderCompletedEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 原样转发到应用事件发布器（发布本身不落库；事务提交后的投递时
     * 序由监听侧事务同步表达）。
     */
    @Override
    public void publishOrderCompleted(OrderCompleted event) {
        applicationEventPublisher.publishEvent(event);
    }
}