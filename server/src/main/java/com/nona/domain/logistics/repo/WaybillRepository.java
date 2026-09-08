package com.nona.domain.logistics.repo;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.persistence.BaseRepository;

import java.util.List;
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

    /**
     * 在途运单全量扫描面（模拟推进器的扫描锚点）：返回全部未签收运单
     * （待发货/已发货/运输中——「一子单一在途」语义下每子单至多一条）。
     * <p>
     * 数据面/业务面分工：本契约仅承载「在途全量」的数据扫描，到期
     * 判定（推进节奏/当前状态分派）收敛在模拟推进器（业务面）——查询
     * 契约不感知节奏配置，避免节奏演进穿过仓储面。
     *
     * @return 全部在途运单（不含已签收终态）；无在途返回空列表
     */
    List<Waybill> findInTransit();
}