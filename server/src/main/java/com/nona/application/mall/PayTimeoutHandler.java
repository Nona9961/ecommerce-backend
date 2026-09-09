package com.nona.application.mall;

import com.nona.inf.timeout.TimeoutHandler;
import com.nona.inf.timeout.TimeoutType;
import org.springframework.stereotype.Component;

/**
 * 支付超时处理器（ORDER_PAY：待支付 30 分钟自动关单回滚，B8.3）：
 * 超时引擎到期回调的<b>薄协调</b>——按类型路由到复用入口
 * {@link CancelOrderUseCase#cancelByTimeout}，不复制业务编排。
 * <p>
 * 幂等契约（引擎恢复重扫可重复 fire）：业务幂等由复用入口内置的幂等
 * 短路保证（主单已取消 → 直接返回成功，不重复推进/回滚/关单）——
 * 本处理器不持有任何业务状态，重复 fire 与单次执行结果等价。
 * <p>
 * 放置：application 层（编排协调位——处理器依赖应用用例，domain 零
 * 框架依赖红线与 inf→application 反向引用禁令共同决定本归属；
 * application→inf.timeout 接口引用已有先例）。引擎按
 * {@link TimeoutType} 路由到本处理器，构造器注入了目标用例（红阶段
 * 用例不注册 bean，本处理器同构不注册；Spring 注册随仓储接线 WU
 * 落位）。
 *
 * @author nona9961
 */
@Component
public class PayTimeoutHandler implements TimeoutHandler<Long> {

    /**
     * 复用入口：支付超时自动取消编排（含幂等短路与提权写段）。
     */
    private final CancelOrderUseCase cancelOrderUseCase;

    /**
     * 构造支付超时处理器。
     *
     * @param cancelOrderUseCase 取消编排用例（复用入口，必填）
     */
    public PayTimeoutHandler(CancelOrderUseCase cancelOrderUseCase) {
        this.cancelOrderUseCase = cancelOrderUseCase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimeoutType type() {
        return TimeoutType.ORDER_PAY;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 薄协调：以 target（主订单 ID）路由到 {@link CancelOrderUseCase#cancelByTimeout}；
     * 幂等由用例侧短路承担，本方法无副作用累积。
     *
     * @param target 到期的业务对象引用（ORDER_PAY = 主订单 ID）
     */
    @Override
    public void fire(Long target) {
        cancelOrderUseCase.cancelByTimeout(target);
    }
}