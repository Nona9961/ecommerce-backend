package com.nona.api.mall;

/**
 * 物流状态（线上契约枚举，接口层冻结——WU-59 回注，形状钉死前端
 * mall-trading.types.ts {@code MallWaybillStatus} 4 值逐名对应，与
 * 后端 WaybillStatus 状态机一致：待发货→已发货→运输中→已签收）。
 * <p>
 * 枚举名即线上 JSON 值（Jackson 同名序列化）；WaybillTrackView.status
 * 与 WaybillView.status 统一承载。
 *
 * @author nona9961
 */
public enum MallWaybillStatus {

    /**
     * 待发货（已建单未发货）。
     */
    PENDING_SHIPMENT,

    /**
     * 已发货。
     */
    SHIPPED,

    /**
     * 运输中。
     */
    IN_TRANSIT,

    /**
     * 已签收（终态）。
     */
    DELIVERED
}