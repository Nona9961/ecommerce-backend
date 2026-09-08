package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * 运单轨迹从表 JPA 仓储（waybill_track 表，从表行，append-only——本
 * 仓储只增删查，不承载字段更新）。
 *
 * @author nona9961
 */
public interface WaybillTrackJpaRepository extends ListCrudRepository<WaybillTrackPO, Long> {

    /**
     * 按归属运单加载轨迹行（rootId 反查，保持追加序）。
     *
     * @param waybillId 运单 ID
     * @return 轨迹行列表；无轨迹返回空列表
     */
    List<WaybillTrackPO> findByWaybillIdOrderByIdAsc(Long waybillId);

    /**
     * 按归属运单删除全部轨迹行（仓储级联删消费面）。
     *
     * @param waybillId 运单 ID
     */
    void deleteByWaybillId(Long waybillId);
}