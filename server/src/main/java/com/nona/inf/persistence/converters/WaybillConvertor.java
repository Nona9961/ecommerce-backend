package com.nona.inf.persistence.converters;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.inf.persistence.po.logistics.WaybillPO;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运单聚合根 ↔ 运单 PO 转换器（主表 waybill，global——租户中立；从表
 * waybill_track 轨迹行集合）。
 * <p>
 * 主表行 = 聚合根身份（id = 运单主键，sub_order_id 业务关联锚点，
 * company/tracking_no 录单必填）；status 状态机唯一可变位（单向相邻
 * 推进收敛在聚合方法 advanceTo）。<b>in_transit 派生态列</b>由本转换器
 * 按状态推导写入：status != DELIVERED → TRUE（每子单至多
 * 一张在途行，uk_waybill_sub_order_in_transit 唯一防线）；DELIVERED
 * （签收）→ NULL（历史行承载，释放锚点后子单可再次发货）；FALSE 永
 * 不写。读路径以 status 重建，in_transit 列值不参与装载。other 参数
 * 为从表轨迹行集合（读路径由仓储 getOther 提供）。
 *
 * @author nona9961
 */
@Component
public class WaybillConvertor
        extends AbstractConvertor<Waybill, WaybillPO, List<WaybillTrackPO>> {

    /**
     * 轨迹行转换器
     */
    private final WaybillTrackConvertor trackConvertor;

    /**
     * 构造运单转换器。
     *
     * @param trackConvertor 轨迹行转换器
     */
    public WaybillConvertor(WaybillTrackConvertor trackConvertor) {
        this.trackConvertor = trackConvertor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected WaybillPO safedConvertToPO(Waybill root) {
        final WaybillPO po = new WaybillPO();
        po.setId(root.getId());
        po.setSubOrderId(root.getSubOrderId());
        po.setCompany(root.getCompany());
        po.setTrackingNo(root.getTrackingNo());
        po.setStatus(root.getStatus());
        po.setInTransit(root.getStatus() == WaybillStatus.DELIVERED ? null : Boolean.TRUE);
        return po;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Waybill safedConvertToRoot(WaybillPO po, List<WaybillTrackPO> trackPOs) {
        final List<WaybillTrackPO> rows = trackPOs == null ? List.of() : trackPOs;
        return new Waybill(po.getId(), po.getSubOrderId(), po.getCompany(),
                po.getTrackingNo(), po.getStatus(),
                rows.stream().map(trackConvertor::toDomain).toList());
    }
}