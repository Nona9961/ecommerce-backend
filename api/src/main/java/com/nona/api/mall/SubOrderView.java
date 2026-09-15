package com.nona.api.mall;

import java.util.List;

/**
 * 子订单视图（店铺维度履约单元，形状钉死前端
 * mall-trading.types.ts {@code SubOrderView}）。
 * <p>
 * 金额四维扁平承载（goodsAmount/freightAmount/discount/paidAmount，
 * 分——api-client 换算为元后组装 {@code amount} 对象下行）。
 *
 * @param subOrderId   子订单 ID
 * @param subOrderNo   子订单号（TD-13 业务单号）
 * @param shopId       归属店铺 ID
 * @param shopName     店铺名（展示「订单归属店铺」）
 * @param status       子单履约状态（SubOrderStatus 8 值，枚举名序列化）
 * @param waybillId    关联运单 ID（已发货后非空，物流入口锚点；可空）
 * @param goodsAmount  商品金额（分）
 * @param freightAmount 运费（分）
 * @param discount     优惠（分，一期恒 0）
 * @param paidAmount   实付（分，商品金额 + 运费 - 优惠）
 * @param items        商品快照行（下单时刻固化）
 * @author nona9961
 */
public record SubOrderView(
        Long subOrderId,
        String subOrderNo,
        Long shopId,
        String shopName,
        MallOrderStatus status,
        Long waybillId,
        long goodsAmount,
        long freightAmount,
        long discount,
        long paidAmount,
        List<OrderItemView> items
) {
}