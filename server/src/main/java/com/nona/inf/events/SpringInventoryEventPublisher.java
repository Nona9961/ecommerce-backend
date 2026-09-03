package com.nona.inf.events;

import com.nona.domain.inventory.ports.InventoryEventPublisher;
import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 库存事件发布端口落地：经 Spring 应用事件发布器在库存事务内发布领域
 * 事件——事件类型为纯领域对象（POJO），AFTER_COMMIT 投递语义由监听侧
 * {@link InventoryEventLogListener} 的事务同步监听表达（@Transactional
 * 用例方法发布，库存事务提交后监听器才收到）。
 * <p>
 * 发布失败不影响库存主链路：事件一期为追踪型消费（可靠性由同库事务
 * 与 CDC 变更外发承担，事件总线不承担可靠性职责）。
 *
 * @author nona9961
 */
@Component
public class SpringInventoryEventPublisher implements InventoryEventPublisher {

    /**
     * Spring 应用事件发布器（进程内事件分发入口）
     */
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 构造发布器。
     *
     * @param applicationEventPublisher 应用事件发布器
     */
    public SpringInventoryEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 原样转发到应用事件发布器（发布本身不落库；事务提交后的投递时
     * 序由监听侧事务同步表达）。
     */
    @Override
    public void publishSellout(SelloutEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 原样转发到应用事件发布器（发布本身不落库；事务提交后的投递时
     * 序由监听侧事务同步表达）。
     */
    @Override
    public void publishRestock(RestockEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}