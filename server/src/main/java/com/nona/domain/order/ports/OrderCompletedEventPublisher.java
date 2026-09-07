package com.nona.domain.order.ports;

/**
 * 完成事件发布端口（order 域跨上下文契约，实现在基础设施层）：完成
 * 事件从确认收货/收货超时编排（应用层用例同事务内）发布——进程内
 * 总线，事务提交后（AFTER_COMMIT）投递，投递时机由实现层经事务同步
 * 语义表达（与 {@link com.nona.domain.inventory.ports.InventoryEventPublisher}
 * 同构）。
 * <p>
 * 判定职责不在本端口：何时发布由完成编排（子单完成推进成功后）调用
 * ——判定语义收敛在编排（领域推进经订单门面），发布机制收敛在基础
 * 设施层。
 *
 * @author nona9961
 */
public interface OrderCompletedEventPublisher {

    /**
     * 发布子单完成事件（子单完成推进成功后，每次完成发布一次）。
     *
     * @param event 完成事件（载荷 = 完成子单 + 归属主单引用 ID）
     */
    void publishOrderCompleted(OrderCompleted event);
}