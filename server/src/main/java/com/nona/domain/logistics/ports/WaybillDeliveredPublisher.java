package com.nona.domain.logistics.ports;

/**
 * 签收事件发布端口（logistics 域跨上下文契约，实现在基础设施层）：
 * 领域事件从模拟推进器推进事务内发布——进程内总线，事务提交后投递
 * （投递时机由实现层经事务同步语义表达，与
 * {@link com.nona.domain.order.ports.OrderCompletedEventPublisher}
 * 同构）。
 * <p>
 * 判定职责不在本端口：何时发布由模拟推进器（运单推进至已签收终态后）
 * 调用——判定语义收敛在推进器（业务面），发布机制收敛在基础设施层。
 *
 * @author nona9961
 */
public interface WaybillDeliveredPublisher {

    /**
     * 发布运单签收事件（运单推进至已签收后，每运单至多一次——终态
     * 闭合不重复发布）。
     *
     * @param event 签收事件（载荷 = 签收运单 + 关联子单引用 ID）
     */
    void publishWaybillDelivered(WaybillDelivered event);
}