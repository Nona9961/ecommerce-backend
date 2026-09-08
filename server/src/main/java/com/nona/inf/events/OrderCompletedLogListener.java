package com.nona.inf.events;

import com.nona.domain.order.ports.OrderCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * 完成事件消费位（Phase-I 日志型消费 + Phase-II 消费方 stub）：
 * {@link TransactionalEventListener} 订阅订单事务提交（AFTER_COMMIT）
 * 后的子单完成事件——消费方仅看到已提交的完成状态（确认收货/收货
 * 超时编排的一致性前提），订单事务成败与事件消费解耦（消费失败不
 * 影响订单主链路）。
 * <p>
 * <b>Phase-I 接线形态（绿阶段实现依据）</b>：与库存事件日志监听
 * （{@code InventoryEventLogListener}）同构——监听方法将日志任务
 * 提交到订单事件异步执行器（虚拟线程调度 + 请求上下文传播装饰器，
 * 上下文传播随执行器装配），仅日志留痕（事件类型 + 完成子单/主单
 * 引用 ID），不承载业务副作用。
 * <p>
 * <b>Phase-II 消费位 stub（本 WU 范围声明，不落实现）</b>：评价资格
 * 授予（review 域，B11.1 仅已完成订单可评价——订单项维度，经
 * subOrderId 回查）、结算入账（settlement 域，完成事件入账归档，
 * B9.4④ 完成/关闭时资金结算完成）、通知中心与统计（notification /
 * analytics 域）接入同一机制（TD-07）——各消费位由对应 Phase-II WU
 * 独立实现，本类不占位。
 *
 * @author nona9961
 */
@Slf4j
@Component
public class OrderCompletedLogListener {

    /**
     * 事件日志统一前缀（定位标识：订单完成事件日志检索面）
     */
    private static final String LOG_PREFIX = "[order-event]";

    /**
     * 事件异步执行器（日志任务调度；上下文传播随执行器装饰器就位）
     */
    private final Executor orderEventExecutor;

    /**
     * 构造日志监听。
     *
     * @param orderEventExecutor 事件异步执行器（{@link OrderCompletedEventConfig}
     *                           装配，虚拟线程调度 + 请求上下文传播装饰器）
     */
    public OrderCompletedLogListener(@Qualifier("orderEventExecutor") Executor orderEventExecutor) {
        this.orderEventExecutor = orderEventExecutor;
    }

    /**
     * 子单完成事件日志消费（事务提交后投递——无事务的发布路径不
     * 触发）：日志任务经执行器异步执行，记录事件类型与完成子单/归属
     * 主单引用 ID；异步消费失败仅告警不影响订单主链路。
     *
     * @param event 子单完成事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCompleted(OrderCompleted event) {
        logEvent(event.getPayload().subOrderId(), event.getPayload().masterOrderId(),
                event.timestamp());
    }

    /**
     * 事件日志消费收尾形态：按事件载荷组装日志任务并异步提交——任务体
     * 与提交动作双层容错（消费/拒绝异常均捕获为告警日志，消费失败不
     * 影响订单主链路）。
     *
     * @param subOrderId    完成子单 ID（事件载荷）
     * @param masterOrderId 归属主单 ID（事件载荷）
     * @param at            事件发生时间戳
     */
    private void logEvent(Long subOrderId, Long masterOrderId, Instant at) {
        submit(() -> log.info(LOG_PREFIX + " {} subOrderId={} masterOrderId={} at={}",
                OrderCompleted.TYPE, subOrderId, masterOrderId, at));
    }

    /**
     * 异步提交日志任务：任务体与提交动作双层容错（消费/拒绝异常均捕获
     * 为告警日志，消费失败不影响订单主链路）。
     *
     * @param task 日志任务（事件载荷已捕获）
     */
    private void submit(Runnable task) {
        try {
            orderEventExecutor.execute(() -> {
                try {
                    task.run();
                } catch (final RuntimeException ex) {
                    log.warn(LOG_PREFIX + " async consume failed: {}", ex.getMessage(), ex);
                }
            });
        } catch (final RuntimeException ex) {
            log.warn(LOG_PREFIX + " async submit failed: {}", ex.getMessage(), ex);
        }
    }
}