package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.order.OrderItemPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 子订单订单项从表 JPA 仓储（order_item 表，从表行）。
 *
 * @author nona9961
 */
public interface OrderItemJpaRepository extends ListCrudRepository<OrderItemPO, Long> {

    /**
     * 按归属子单加载订单项行（rootId 反查，保持创建序）。
     *
     * @param subOrderId 子订单 ID
     * @return 订单项行列表；无条目返回空列表
     */
    List<OrderItemPO> findBySubOrderIdOrderByIdAsc(Long subOrderId);

    /**
     * 按归属子单删除全部订单项行（仓储级联删消费面）。
     *
     * @param subOrderId 子订单 ID
     */
    void deleteBySubOrderId(Long subOrderId);
}