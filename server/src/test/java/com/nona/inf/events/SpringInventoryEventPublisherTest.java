package com.nona.inf.events;

import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 库存事件发布端口落地单元测试：售罄/恢复事件原样转发到 Spring 应用
 * 事件发布器（发布动作最小化——判定语义收敛在领域统一触发点，本类仅
 * 承载发布转发；事务提交后投递的时序由监听侧事务同步表达）。
 *
 * @author nona9961
 */
class SpringInventoryEventPublisherTest {

    /**
     * 被测应用事件发布器（mock）
     */
    private final ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);

    /**
     * 被测发布器（与应用事件发布器同实例组装）
     */
    private final SpringInventoryEventPublisher publisher =
            new SpringInventoryEventPublisher(applicationEventPublisher);

    /**
     * happy：售罄事件原样转发到应用事件发布器（事件对象同一引用，不
     * 改写载荷与时间戳）。
     */
    @Test
    @DisplayName("售罄事件原样转发到应用事件发布器")
    void publishSellout_forwardsEventAsIs() {
        final SelloutEvent event = new SelloutEvent(88001L);

        publisher.publishSellout(event);

        verify(applicationEventPublisher).publishEvent(event);
    }

    /**
     * happy：恢复事件原样转发到应用事件发布器（事件对象同一引用）。
     */
    @Test
    @DisplayName("恢复事件原样转发到应用事件发布器")
    void publishRestock_forwardsEventAsIs() {
        final RestockEvent event = new RestockEvent(88002L);

        publisher.publishRestock(event);

        verify(applicationEventPublisher).publishEvent(event);
    }
}