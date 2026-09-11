package com.nona.application.mall;

import com.nona.inf.timeout.TimeoutHandler;
import com.nona.inf.timeout.TimeoutType;
import org.springframework.stereotype.Component;

/**
 * 发货超时处理器（ORDER_SHIP：已支付子订单 3 天未发货自动关单退款）：超时引擎到期回调的<b>薄协调</b>——按类型路由到复用入口
 * {@link RefundUseCase#refundByShipTimeout}，不复制业务编排。
 * <p>
 * 幂等契约（引擎恢复重扫可重复 fire）：业务幂等由复用入口内置的幂等
 * 短路保证（子单状态非 PAID → 直接返回 null；已有退款单 → 短路 null，
 * 不重复建单）——本处理器不持有任何业务状态，重复 fire 与单次执行
 * 结果等价。
 * <p>
 * 放置：application 层（编排协调位——处理器依赖应用用例，domain 零
 * 框架依赖红线与 inf→application 反向引用禁令共同决定本归属；
 * application→inf.timeout 接口引用已有先例）。引擎按
 * {@link TimeoutType} 路由到本处理器，构造器注入了目标用例（均已
 * 注册为容器 bean）。
 *
 * @author nona9961
 */
@Component
public class ShipTimeoutHandler implements TimeoutHandler<Long> {

    /**
     * 复用入口：发货超时系统退款编排（含 closeByTimeout、库存回补
     * 语义与幂等短路）。
     */
    private final RefundUseCase refundUseCase;

    /**
     * 构造发货超时处理器。
     *
     * @param refundUseCase 退款编排用例（复用入口，必填）
     */
    public ShipTimeoutHandler(RefundUseCase refundUseCase) {
        this.refundUseCase = refundUseCase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimeoutType type() {
        return TimeoutType.ORDER_SHIP;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 薄协调：以 target（子订单 ID）路由到
     * {@link RefundUseCase#refundByShipTimeout}；幂等由用例侧短路承担，
     * 本方法无副作用累积。
     *
     * @param target 到期的业务对象引用（ORDER_SHIP = 子订单 ID）
     */
    @Override
    public void fire(Long target) {
        refundUseCase.refundByShipTimeout(target);
    }
}