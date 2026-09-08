package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.order.MasterOrderPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

/**
 * 主订单主表 JPA 仓储（master_order 表，主表行）。
 *
 * @author nona9961
 */
public interface MasterOrderJpaRepository extends ListCrudRepository<MasterOrderPO, Long> {

    /**
     * 按订单号查询主表行（业务单号唯一）。
     *
     * @param orderNo 订单号
     * @return 主表行；不存在返回空
     */
    Optional<MasterOrderPO> findByOrderNo(String orderNo);
}