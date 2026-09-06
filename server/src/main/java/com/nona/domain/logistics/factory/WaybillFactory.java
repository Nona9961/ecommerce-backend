package com.nona.domain.logistics.factory;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;
import com.nona.util.IDUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 运单聚合根工厂：运单与轨迹条目的创建入口（ID 生成 + 装配形态校验）。
 * <p>
 * 创建语义（S10.3 商家发货录入承运公司与运单号）：发货编排
 * （logistics.createWaybill + order.markShipped 同事务）先按子单在途
 * 运单查询（{@code findInTransitBySubOrderId}）守卫「一子单一在途」
 * 不变量（重复发货拒绝，logistics.sub_order_conflict），命中不再创建；
 * 工厂本层只校验装配形态（必填/非空），不感知跨域状态。
 * <p>
 * 校验分层：工厂只校验装配形态（子单引用/公司/单号/时间必填）；状态机
 * 迁移守卫与轨迹时间线一致性收敛在聚合构造与聚合方法；「一子单一在途」
 * 的完整拒绝在发货编排 + 数据库在途唯一约束（绿阶段落位）。
 *
 * @author nona9961
 */
@Component
public class WaybillFactory {

    /**
     * 初始轨迹描述文案（运单创建节点）。
     */
    private static final String INITIAL_TRACK_DESCRIPTION = "运单创建，等待发货";

    /**
     * 创建运单（状态定型为待发货，自带初始轨迹节点）。
     *
     * @param subOrderId 关联子单 ID（必填）
     * @param company    承运公司（必填非空）
     * @param trackingNo 运单号（必填非空）
     * @param createdAt  创建时间（必填，初始轨迹节点时间）
     * @return 新建运单（待发货，待仓储保存）
     */
    public Waybill createWaybill(Long subOrderId, String company, String trackingNo,
                                 LocalDateTime createdAt) {
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                subOrderId, "运单关联子单 ID 不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_COMPANY_BLANK.code(),
                company != null && !company.isBlank(), "承运公司不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.LOGISTICS_TRACKING_NO_BLANK.code(),
                trackingNo != null && !trackingNo.isBlank(), "运单号不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                createdAt, "运单创建时间不能为空");
        final Long waybillId = IDUtils.generateID();
        final WaybillTrack initialTrack = new WaybillTrack(IDUtils.generateID(), waybillId,
                WaybillStatus.PENDING_SHIPMENT, createdAt, INITIAL_TRACK_DESCRIPTION);
        return new Waybill(waybillId, subOrderId, company, trackingNo,
                WaybillStatus.PENDING_SHIPMENT, java.util.List.of(initialTrack));
    }

    /**
     * 创建轨迹条目（状态推进路径的轨迹装配：调用方构造目标状态轨迹后
     * 传入 {@link Waybill#advanceTo}；模拟推进器/物流公司回调使用）。
     *
     * @param waybill     归属运单（必填；轨迹 waybillId 取运单主键）
     * @param status      目标状态（必填，须为运单当前状态的下一状态——推进守卫在聚合）
     * @param occurredAt  发生时间（必填）
     * @param description 节点描述（可空）
     * @return 轨迹条目（尚未追加进运单，由 advanceTo 校验后追加）
     */
    public WaybillTrack createTrack(Waybill waybill, WaybillStatus status,
                                    LocalDateTime occurredAt, String description) {
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_WAYBILL_INVALID.code(),
                waybill, "轨迹归属运单不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code(),
                status, "轨迹状态不能为空");
        BusinessAssert.assertNonNull(EcommerceBusinessCode.LOGISTICS_TRACK_INVALID.code(),
                occurredAt, "轨迹发生时间不能为空");
        return new WaybillTrack(IDUtils.generateID(), waybill.getId(), status,
                occurredAt, description);
    }
}