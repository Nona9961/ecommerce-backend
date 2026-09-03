package com.nona.domain.inventory.ports;

/**
 * 库存事件发布端口（inventory 域跨上下文契约，实现在基础设施层）：
 * 领域事件从库存事务内发布（进程内总线，事务提交后投递——投递时机由
 * 实现层经事务同步语义表达）。
 * <p>
 * 判定职责不在本端口：何时发布由统一触发点（领域服务）基于变更流水
 * 快照判定后调用，本端口仅承载「发布」这一最小动作——判定语义收敛在
 * 领域层、发布机制收敛在基础设施层。
 *
 * @author nona9961
 */
public interface InventoryEventPublisher {

    /**
     * 发布售罄事件（变更后 available=0 且 held=0）。
     *
     * @param event 售罄事件（荷载售罄 SKU）
     */
    void publishSellout(SelloutEvent event);

    /**
     * 发布可售恢复事件（变更前已售罄、变更后恢复可售）。
     *
     * @param event 可售恢复事件（荷载恢复 SKU）
     */
    void publishRestock(RestockEvent event);
}