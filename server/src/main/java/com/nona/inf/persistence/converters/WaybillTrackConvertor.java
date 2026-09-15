package com.nona.inf.persistence.converters;

import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import org.springframework.stereotype.Component;

/**
 * 运单轨迹实体 ↔ 运单轨迹 PO 转换器（waybill_track 从表行映射，
 * append-only）。
 * <p>
 * 字段逐列一一对应；occurred_at 领域即 {@link java.time.LocalDateTime}
 * （simulator 本机时钟语义），直映射零转换；description
 * 可空直透。PO 行 id 与领域 id 一一对应。
 *
 * @author nona9961
 */
@Component
public class WaybillTrackConvertor implements PoConverter<WaybillTrack, WaybillTrackPO> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<WaybillTrack> domainClass() {
        return WaybillTrack.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<WaybillTrackPO> poClass() {
        return WaybillTrackPO.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WaybillTrackPO toPO(WaybillTrack domain) {
        final WaybillTrackPO po = new WaybillTrackPO();
        po.setId(domain.getId());
        po.setWaybillId(domain.getWaybillId());
        po.setStatus(domain.getStatus());
        po.setOccurredAt(domain.getOccurredAt());
        po.setDescription(domain.getDescription());
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WaybillTrack toDomain(WaybillTrackPO po) {
        return new WaybillTrack(po.getId(), po.getWaybillId(), po.getStatus(),
                po.getOccurredAt(), po.getDescription());
    }
}