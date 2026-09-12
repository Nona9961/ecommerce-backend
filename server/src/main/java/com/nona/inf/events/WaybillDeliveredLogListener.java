package com.nona.inf.events;

import com.nona.domain.logistics.ports.WaybillDelivered;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * 签收事件消费位（日志型消费 + 与自动完成联动消费并存）：
 * {@link TransactionalEventListener} 订阅推进事务提交（AFTER_COMMIT）
 * 后的运单签收事件——消费方仅看到已提交的签收状态（模拟推进/订单
 * 联动的时序前提），物流推进事务成败与事件消费解耦（消费失败不影响
 * 推进主链路）。
 * <p>
 * <b>接线形态</b>：与完成事件日志监听
 * （{@code OrderCompletedLogListener}）同构——监听方法将日志任务
 * 提交到物流事件异步执行器（虚拟线程调度 + 请求上下文传播装饰器，
 * 上下文传播随执行器装配），仅日志留痕（事件类型 + 签收运单/关联
 * 子单引用 ID），不承载业务副作用（自动完成联动由
 * {@code WaybillDeliveredReceiptListener} 承载）。
 * <p>
 * <b>装配声明</b>：行为方法体已接线（日志消费）；执行器 bean
 * （{@code waybillEventExecutor}）由 {@link WaybillDeliveredEventConfig}
 * 装配。
 *
 * @author nona9961
 */
@Slf4j
@Component
public class WaybillDeliveredLogListener {

    /**
     * 事件日志统一前缀（定位标识：签收事件日志检索面）
     */
    private static final String LOG_PREFIX = "[waybill-event]";

    /**
     * 事件异步执行器（日志任务调度；上下文传播随执行器装饰器就位）
     */
    private final Executor waybillEventExecutor;

    /**
     * 构造日志监听。
     *
     * @param waybillEventExecutor 事件异步执行器（{@link WaybillDeliveredEventConfig}
     *                             装配，虚拟线程调度 + 请求上下文传播装饰器）
     */
    public WaybillDeliveredLogListener(@Qualifier("waybillEventExecutor") Executor waybillEventExecutor) {
        this.waybillEventExecutor = waybillEventExecutor;
    }

    /**
     * 运单签收事件日志消费（事务提交后投递——无事务的发布路径不
     * 触发）：日志任务经执行器异步执行，记录事件类型与签收运单/关联
     * 子单引用 ID；异步消费失败仅告警不影响推进主链路。
     *
     * @param event 运单签收事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWaybillDelivered(WaybillDelivered event) {
        logEvent(event.getPayload().waybillId(), event.getPayload().subOrderId(),
                event.timestamp());
    }

    /**
     * 事件日志消费收尾形态：按事件载荷组装日志任务并异步提交——任务体
     * 与提交动作双层容错（消费/拒绝异常均捕获为告警日志，消费失败不
     * 影响推进主链路）。
     *
     * @param waybillId  签收运单 ID（事件载荷）
     * @param subOrderId 关联子单 ID（事件载荷）
     * @param at         事件发生时间戳
     */
    private void logEvent(Long waybillId, Long subOrderId, Instant at) {
        submit(() -> log.info(LOG_PREFIX + " {} waybillId={} subOrderId={} at={}",
                WaybillDelivered.TYPE, waybillId, subOrderId, at));
    }

    /**
     * 异步提交日志任务：任务体与提交动作双层容错（消费/拒绝异常均捕获
     * 为告警日志，消费失败不影响推进主链路）。
     *
     * @param task 日志任务（事件载荷已捕获）
     */
    private void submit(Runnable task) {
        try {
            waybillEventExecutor.execute(() -> {
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