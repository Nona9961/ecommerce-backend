package com.nona.inf.persistence.repository.jpa;

import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.inf.persistence.po.order.SubOrderPO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 子订单主表 JPA 仓储（sub_order 表，tenant=shopId 主表行）。
 *
 * @author nona9961
 */
public interface SubOrderJpaRepository extends ListCrudRepository<SubOrderPO, Long> {

    /**
     * 按归属主订单加载子单行（主单维度反查，保持创建序）。
     *
     * @param masterOrderId 主订单 ID
     * @return 子单行列表；无子单返回空列表
     */
    List<SubOrderPO> findByMasterOrderIdOrderByIdAsc(Long masterOrderId);

    /**
     * 超时扫描面（走 (status, timeout_at) 复合索引）：状态为预期态且
     * 截止时间已到期（含等号边界）的候选，按截止时间升序至多 limit 条。
     *
     * @param status   预期态（发货超时 = PAID；收货超时 = SHIPPED）
     * @param timeoutAt 扫描时刻（含等号）
     * @param limit    单轮扫描上限
     * @return 到期候选；无到期返回空列表
     */
    List<SubOrderPO> findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
            SubOrderStatus status, LocalDateTime timeoutAt, org.springframework.data.domain.Limit limit);

    /**
     * 店铺订单分页（全量）：店铺业务条件 + 租户过滤双层定位（冻结），
     * 创建时间倒序 + 主键倒序稳定分页。本方法为「全部」tab
     * 承载面（statuses 为 null/空集合时调用）。
     *
     * @param shopId   归属店铺 ID
     * @param pageable 分页参数
     * @return 主表行分页结果（租户过滤内，跨店空页）
     */
    Page<SubOrderPO> findByShopIdOrderByCreateTimeDescIdDesc(Long shopId,
                                                             Pageable pageable);

    /**
     * 店铺订单分页（状态多值过滤）：shop_id 显式条件 + 租户过滤双层
     * 防线（冻结；statuses 非空集合承载面），创建时间倒序 +
     * 主键倒序稳定分页。
     *
     * @param shopId   归属店铺 ID
     * @param statuses 状态多值集合（非空）
     * @param pageable 分页参数
     * @return 主表行分页结果
     */
    Page<SubOrderPO> findByShopIdAndStatusInOrderByCreateTimeDescIdDesc(
            Long shopId, Collection<SubOrderStatus> statuses, Pageable pageable);

    /**
     * 店铺订单计数（全量，对应
     * {@link #findByShopIdOrderByCreateTimeDescIdDesc} 条件）。
     *
     * @param shopId 归属店铺 ID
     * @return 命中主表行数
     */
    long countByShopId(Long shopId);

    /**
     * 店铺订单计数（状态多值过滤，对应
     * {@link #findByShopIdAndStatusInOrderByCreateTimeDescIdDesc} 条件）。
     *
     * @param shopId   归属店铺 ID
     * @param statuses 状态多值集合（非空）
     * @return 命中主表行数
     */
    long countByShopIdAndStatusIn(Long shopId, Collection<SubOrderStatus> statuses);
}