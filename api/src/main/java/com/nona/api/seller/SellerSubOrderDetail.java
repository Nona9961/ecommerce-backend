package com.nona.api.seller;

import java.util.List;

/**
 * 本店子订单详情响应体（商家订单详情分组：商品快照/金额明细/地址/物流
 * 信息，WU-47 约定形状：GET /seller/orders/{subOrderId}）。
 * <p>
 * 形态契约：status 为履约状态枚举名；金额一律分（前端换算为元）；
 * waybill 为运单概要——未发货子单为 null（无运单）、已发货子单非空
 * （公司/单号/物流状态展示）；createTime 为下单时间（主表审计时间，
 * ISO 8601 字符串）。跨店铺访问按不存在呈现（404，fail-closed——
 * 查询编排承载归属校验，不泄露归属）。
 *
 * @param subOrderId    子订单 ID
 * @param subOrderNo    子订单号
 * @param masterOrderId 归属主订单 ID
 * @param status        履约状态（SubOrderStatus 枚举名）
 * @param shopId        归属店铺 ID（当前店铺）
 * @param address       收货地址快照（脱敏可展开）
 * @param amount        金额明细
 * @param items         订单项快照列表（禁改）
 * @param waybill       运单概要（未发货为 null）
 * @param createTime    下单时间（ISO 8601）
 * @author nona9961
 */
public record SellerSubOrderDetail(
        Long subOrderId,
        String subOrderNo,
        Long masterOrderId,
        String status,
        Long shopId,
        SellerOrderAddress address,
        SellerOrderAmount amount,
        List<SellerOrderItemView> items,
        SellerWaybillView waybill,
        String createTime
) {
}