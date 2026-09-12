package com.nona.inf.timeout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时类型注册契约：类型全集与超时数值冻结（业务拍板：支付 30 分钟 /
 * 发货 3 天 / 收货 7 天）。数值是业务侧注册截止时间（deadline =
 * 业务时点 + duration）的换算依据，冻结后不得漂移。
 */
class TimeoutTypeUnitTest {

    @Test
    @DisplayName("支付超时为 30 分钟（1800000ms）")
    void orderPay_is30Minutes() {
        assertThat(TimeoutType.ORDER_PAY.durationMillis()).isEqualTo(30L * 60 * 1000);
    }

    @Test
    @DisplayName("发货超时为 3 天（259200000ms）")
    void orderShip_is3Days() {
        assertThat(TimeoutType.ORDER_SHIP.durationMillis()).isEqualTo(3L * 24 * 60 * 60 * 1000);
    }

    @Test
    @DisplayName("收货超时为 7 天（604800000ms）")
    void orderReceive_is7Days() {
        assertThat(TimeoutType.ORDER_RECEIVE.durationMillis()).isEqualTo(7L * 24 * 60 * 60 * 1000);
    }

    @Test
    @DisplayName("引擎注册三类超时场景且类型互异")
    void threeTypesDeclared_distinct() {
        assertThat(TimeoutType.values())
                .extracting(TimeoutType::name)
                .containsExactly("ORDER_PAY", "ORDER_SHIP", "ORDER_RECEIVE");
    }
}