package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.logistics.WaybillPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * 运单主表 JPA 仓储（waybill 表，主表行，global）。
 *
 * @author nona9961
 */
public interface WaybillJpaRepository extends ListCrudRepository<WaybillPO, Long> {

    /**
     * 按子单加载在途运单行（「一子单一在途」查询锚点——in_transit=1
     * 行每子单至多一张，唯一约束 DB 兜底）。
     *
     * @param subOrderId 子单 ID
     * @return 在途运单行；不存在返回空
     */
    Optional<WaybillPO> findBySubOrderIdAndInTransitTrue(Long subOrderId);

    /**
     * 在途运单全量扫描面（in_transit=1 全量，模拟推进器扫描锚点）。
     *
     * @return 全部在途运单行；无在途返回空列表
     */
    List<WaybillPO> findByInTransitTrue();

    /**
     * 按子单装载最新运单行（任意状态，含签收终态；WU-55 冻结——
     * 订单详情/物流展示面锚点）。主键倒序第一条（Snowflake 主键
     * 单调近似创建序；业务常态一子单一张运单，历史并存行取最新）。
     *
     * @param subOrderId 子单 ID
     * @return 最新运单行；不存在返回空
     */
    Optional<WaybillPO> findFirstBySubOrderIdOrderByIdDesc(Long subOrderId);
}