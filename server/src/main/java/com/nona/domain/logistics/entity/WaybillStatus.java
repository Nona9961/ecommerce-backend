package com.nona.domain.logistics.entity;

import com.nona.exceptions.BusinessCode;
import com.nona.exceptions.BusinessException;

/**
 * 运单履约状态（Waybill 聚合状态字段，状态机值定型）。
 * <p>
 * 状态机（迁移守卫全部收敛在聚合方法 {@link Waybill#advanceTo(WaybillTrack)}，
 * 非法迁移抛 {@link com.nona.exceptions.BusinessException}，
 * 业务码 {@code logistics.status_illegal}）：
 * <pre>
 * pending shipment → shipped → in transit → delivered
 * </pre>
 * 语义钉死（红阶段契约）：
 * <ol>
 *     <li>单向相邻推进：每次迁移只能前进到下一个状态（跳级/重复/回退/
 *         终态再推进均为非法，聚合守卫拒绝）；</li>
 *     <li>delivered 为终态（签收时间线闭合，无任何出边）；</li>
 *     <li>在途判定：{@code status != DELIVERED} 即在途（待发货/已发货/
 *         运输中均算在途——未签收期间禁止重复发货，{@code isInTransit}）。</li>
 * </ol>
 *
 * @author nona9961
 */
public enum WaybillStatus {

    /**
     * 待发货：运单创建后的初始态（商家已录入公司/单号，等待发出）。
     */
    PENDING_SHIPMENT,

    /**
     * 已发货：货物已交承运发出。
     */
    SHIPPED,

    /**
     * 运输中：货物运输途中。
     */
    IN_TRANSIT,

    /**
     * 已签收：买家签收（终态，时间线闭合）。
     */
    DELIVERED;

    /**
     * 按枚举名解析运单状态（查询参数/筛选映射用）；null 或未知值拒绝。
     *
     * @param name 枚举名（如 PENDING_SHIPMENT）；null 或未知值拒绝
     * @return 匹配的运单状态
     * @throws BusinessException 非法状态（400 generic.validation_failed）
     */
    public static WaybillStatus fromName(String name) {
        for (WaybillStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new BusinessException(BusinessCode.VALIDATION_FAILED.code(), "非法运单状态", 400);
    }
}