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
}