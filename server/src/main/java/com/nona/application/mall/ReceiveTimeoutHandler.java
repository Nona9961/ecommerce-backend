package com.nona.application.mall;

import com.nona.inf.timeout.TimeoutHandler;
import com.nona.inf.timeout.TimeoutType;
import org.springframework.stereotype.Component;

/**
 * 收货超时处理器（ORDER_RECEIVE：已发货子订单 7 天未确认收货自动完成）：超时引擎到期回调的<b>薄协调</b>——按类型路由到复用入口
 * {@link ConfirmReceiptUseCase#autoCompleteByTimeout}，不复制业务编排。
 * <p>
 * 幂等契约（引擎恢复重扫可重复 fire）：业务幂等由复用入口内置的幂等
 * 短路保证（子单已完成 → 直接返回成功且不重复发布完成事件）——本
 * 处理器不持有任何业务状态，重复 fire 与单次执行结果等价。
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
public class ReceiveTimeoutHandler implements TimeoutHandler<Long> {

    /**
     * 复用入口：收货超时自动完成编排（含完成事件发布与幂等短路）。
     */
    private final ConfirmReceiptUseCase confirmReceiptUseCase;

    /**
     * 构造收货超时处理器。
     *
     * @param confirmReceiptUseCase 确认收货编排用例（复用入口，必填）
     */
    public ReceiveTimeoutHandler(ConfirmReceiptUseCase confirmReceiptUseCase) {
        this.confirmReceiptUseCase = confirmReceiptUseCase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimeoutType type() {
        return TimeoutType.ORDER_RECEIVE;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 薄协调：以 target（子订单 ID）路由到
     * {@link ConfirmReceiptUseCase#autoCompleteByTimeout}；幂等由用例侧
     * 短路承担，本方法无副作用累积。
     *
     * @param target 到期的业务对象引用（ORDER_RECEIVE = 子订单 ID）
     */
    @Override
    public void fire(Long target) {
        confirmReceiptUseCase.autoCompleteByTimeout(target);
    }
}