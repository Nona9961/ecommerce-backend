package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Collection;
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

    /**
     * 买家订单分页（全量）：创建时间倒序 + 主键倒序（WU-55 冻结排序）。
     * 本方法为「全部」tab 承载面——状态不过滤（调用方在 statuses
     * 为 null/空集合时走本方法）。
     *
     * @param buyerId  归属买家账号 ID
     * @param pageable 分页参数（Plant offset/limit 换算）
     * @return 主表行分页结果
     */
    Page<MasterOrderPO> findByBuyerIdOrderByCreateTimeDescIdDesc(Long buyerId,
                                                                 Pageable pageable);

    /**
     * 买家订单分页（状态多值过滤）：创建时间倒序 + 主键倒序
     * （WU-55 冻结排序；statuses 非空集合承载面——调用方归一化
     * 空集合为全量分支）。
     *
     * @param buyerId  归属买家账号 ID
     * @param statuses 状态多值集合（非空）
     * @param pageable 分页参数
     * @return 主表行分页结果
     */
    Page<MasterOrderPO> findByBuyerIdAndStatusInOrderByCreateTimeDescIdDesc(
            Long buyerId, Collection<MasterOrderStatus> statuses, Pageable pageable);

    /**
     * 买家订单计数（全量，对应
     * {@link #findByBuyerIdOrderByCreateTimeDescIdDesc} 条件）。
     *
     * @param buyerId 归属买家账号 ID
     * @return 命中主表行数
     */
    long countByBuyerId(Long buyerId);

    /**
     * 买家订单计数（状态多值过滤，对应
     * {@link #findByBuyerIdAndStatusInOrderByCreateTimeDescIdDesc} 条件）。
     *
     * @param buyerId  归属买家账号 ID
     * @param statuses 状态多值集合（非空）
     * @return 命中主表行数
     */
    long countByBuyerIdAndStatusIn(Long buyerId, Collection<MasterOrderStatus> statuses);
}