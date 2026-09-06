package com.nona.domain.logistics.repo;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.persistence.BaseRepository;

import java.util.Optional;

/**
 * 运单仓储接口：运单的持久化契约（waybill 主表 + waybill_track 从表，
 * global——租户中立，经 subOrderId 关联订单域）。
 * <p>
 * 实现在基础设施层（继承 DifferRepository：主表快照追踪 + 变更集驱动
 * 落库——轨迹从表条目追加插行/状态变更整行更新）。领域层只依赖本契约，
 * 不感知 JPA。
 * <p>
 * 查询契约扩展遵循「契约演进只增不改」：按子单维度查询/列表/分页/平台
 * 跨店视图随消费方工作单元声明，本阶段只冻结「一子单一在途」查询
 * 锚点与创建/装载契约面（BaseRepository）。
 *
 * @author nona9961
 */
public interface WaybillRepository extends BaseRepository<Long, Waybill> {

    /**
     * 按子单加载在途运单（「一子单一在途」不变量的查询锚点——发货编排
     * 创建运单前查询，命中即拒绝重复发货
     * （{@code logistics.sub_order_conflict}））。
     *
     * @param subOrderId 子单 ID
     * @return 该子单的在途运单（未签收）；不存在返回空
     */
    Optional<Waybill> findInTransitBySubOrderId(Long subOrderId);
}