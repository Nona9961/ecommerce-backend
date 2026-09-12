package com.nona.api.mall;

import java.util.List;

/**
 * 运单视图（物流跟踪页，WU-59 回注——形状钉死前端
 * mall-trading.types.ts {@code WaybillView}）。
 * <p>
 * 关联子单商品行由后端装配（子单快照 items 原样呈现）；subOrderNo /
 * shopId / shopName 为子单维度展示冗余（物流页头部信息），经子单装载
 * 装配（含买家归属校验）。
 *
 * @param waybillId   运单 ID
 * @param subOrderId  关联子单 ID
 * @param company     物流公司
 * @param trackingNo  运单号
 * @param status      运单状态（MallWaybillStatus 4 值）
 * @param tracks      追加轨迹（按推进序，最新在后）
 * @param subOrderNo  关联子单号（TD-13 业务单号）
 * @param shopId      子单归属店铺 ID
 * @param shopName    子单归属店铺名
 * @param items       关联商品行（子单快照）
 * @author nona9961
 */
public record WaybillView(
        Long waybillId,
        Long subOrderId,
        String company,
        String trackingNo,
        MallWaybillStatus status,
        List<WaybillTrackView> tracks,
        String subOrderNo,
        Long shopId,
        String shopName,
        List<OrderItemView> items
) {
}