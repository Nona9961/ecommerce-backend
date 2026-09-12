package com.nona.inf.events;

import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * 库存领域事件日志监听：{@link TransactionalEventListener}
 * 订阅库存事务提交（AFTER_COMMIT）后的售罄/恢复事件——消费方仅看到已
 * 提交的库存状态（自动下架联动的一致性前提），库存事务成败与事件消费
 * 解耦（消费失败不影响库存主链路）。
 * <p>
 * 异步执行：监听方法将日志任务提交到 {@link InventoryEventConfig} 装配的
 * 事件执行器（虚拟线程调度 + 请求上下文传播装饰器——租户上下文随任务
 * 传递，跨线程消费不丢失视角）；本监听仅日志留痕（售罄自动下架由商品
 * 域监听器独立装配消费，不占用本类职责）。
 *
 * @author nona9961
 */
@Slf4j
@Component
public class InventoryEventLogListener {

    /**
     * 事件日志统一前缀（定位标识：库存事件日志检索面）
     */
    private static final String LOG_PREFIX = "[inventory-event]";

    /**
     * 事件异步执行器（日志任务调度；上下文传播随执行器装饰器就位）
     */
    private final Executor stockEventExecutor;

    /**
     * 构造日志监听。
     *
     * @param stockEventExecutor 事件异步执行器
     */
    public InventoryEventLogListener(@Qualifier("stockEventExecutor") Executor stockEventExecutor) {
        this.stockEventExecutor = stockEventExecutor;
    }

    /**
     * 售罄事件日志消费（事务提交后投递——无事务的发布路径不触发）：
     * 日志任务经执行器异步执行，记录事件类型与售罄 SKU；异步消费失败
     * 仅告警不影响库存主链路。
     *
     * @param event 售罄事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSellout(SelloutEvent event) {
        logEvent("sellout", event.getPayload().skuId(), event.timestamp());
    }

    /**
     * 可售恢复事件日志消费（事务提交后投递——无事务的发布路径不触发）：
     * 日志任务经执行器异步执行，记录事件类型与恢复 SKU；异步消费失败
     * 仅告警不影响库存主链路。
     *
     * @param event 可售恢复事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRestock(RestockEvent event) {
        logEvent("restock", event.getPayload().skuId(), event.timestamp());
    }

    /**
     * 事件日志消费（售罄/恢复共用的收尾形态）：按事件类型组装日志任务
     * 并异步提交——任务体与提交动作双层容错（消费/拒绝异常均捕获为告警
     * 日志，消费失败不影响库存主链路）。
     *
     * @param action 事件类型动作词（sellout/restock，日志文本定位用）
     * @param skuId  事件载荷 SKU ID
     * @param at     事件发生时间戳
     */
    private void logEvent(String action, Long skuId, Instant at) {
        submit(() -> log.info(LOG_PREFIX + " {} skuId={} at={}", action, skuId, at));
    }

    /**
     * 异步提交日志任务：任务体与提交动作双层容错（消费/拒绝异常均捕获
     * 为告警日志，消费失败不影响库存主链路）。
     *
     * @param task 日志任务（事件载荷已捕获）
     */
    private void submit(Runnable task) {
        try {
            stockEventExecutor.execute(() -> {
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