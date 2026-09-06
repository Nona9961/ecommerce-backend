package com.nona.inf.timeout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 处理器注册机制契约（默认注册表）：处理器按超时类型注册并路由；
 * 未注册类型取用明确拒绝（装配缺失 fail-fast，防止静默丢弃到期任务）。
 */
class TimeoutHandlerRegistryTest {

    @Test
    @DisplayName("注册的处理器可按类型路由取回")
    void get_returnsRegisteredHandlerByType() {
        TimeoutTestKit.FakeHandler payHandler = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutHandlerRegistry registry = new DefaultTimeoutHandlerRegistry(List.of(payHandler));

        assertThat(registry.get(TimeoutType.ORDER_PAY)).isSameAs(payHandler);
    }

    @Test
    @DisplayName("多类型同时注册互不干扰")
    void get_returnsCorrectHandlerAmongMany() {
        TimeoutTestKit.FakeHandler pay = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_PAY);
        TimeoutTestKit.FakeHandler ship = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_SHIP);
        TimeoutTestKit.FakeHandler receive = new TimeoutTestKit.FakeHandler(TimeoutType.ORDER_RECEIVE);
        TimeoutHandlerRegistry registry = new DefaultTimeoutHandlerRegistry(List.of(pay, ship, receive));

        assertThat(registry.get(TimeoutType.ORDER_RECEIVE)).isSameAs(receive);
        assertThat(registry.get(TimeoutType.ORDER_SHIP)).isSameAs(ship);
        assertThat(registry.get(TimeoutType.ORDER_PAY)).isSameAs(pay);
    }

    @Test
    @DisplayName("未注册类型取用明确拒绝（装配缺失）")
    void get_missingType_rejects() {
        TimeoutHandlerRegistry registry = new DefaultTimeoutHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.get(TimeoutType.ORDER_RECEIVE))
                .isInstanceOf(IllegalStateException.class);
    }
}