package com.nona.inf.timeout;

import java.time.Duration;

/**
 * 超时类型：注册到超时调度引擎的业务超时场景定义。
 * <p>
 * 枚举承载两类信息：类型标识（引擎按类型路由到注册的处理器与数据端口，
 * 与业务表冗余截止时间列的 timeout_type 取值同源）与超时数值
 * （业务侧注册截止时间时使用：deadline = 业务时点 + duration）。
 * 三类场景与数值已拍板：支付超时 30 分钟 / 发货超时 3 天 / 收货超时 7 天。
 *
 * @author nona9961
 */
public enum TimeoutType {

    /**
     * 支付超时：待支付订单 30 分钟未完成支付 → 自动关单回滚。
     */
    ORDER_PAY(Duration.ofMinutes(30)),

    /**
     * 发货超时：已支付子订单 3 天未发货 → 自动关闭并走退款（库存回补）。
     */
    ORDER_SHIP(Duration.ofDays(3)),

    /**
     * 收货超时：已发货子订单 7 天未确认收货 → 自动完成。
     */
    ORDER_RECEIVE(Duration.ofDays(7));

    /**
     * 超时数值（自业务时点起算的截止时长）。
     */
    private final Duration duration;

    /**
     * @param duration 超时数值
     */
    TimeoutType(Duration duration) {
        this.duration = duration;
    }

    /**
     * 超时数值毫秒值（deadline 换算与入库使用）。
     *
     * @return 毫秒
     */
    public long durationMillis() {
        return duration.toMillis();
    }
}