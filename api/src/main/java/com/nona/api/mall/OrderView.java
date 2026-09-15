package com.nona.api.mall;

import java.util.List;

/**
 * 订单视图（列表与详情共用形状，形状钉死前端
 * mall-trading.types.ts {@code OrderView}）。
 * <p>
 * 金额四维扁平承载（goodsAmount/freightAmount/discount/paidAmount，
 * 分——api-client 换算为元后组装 {@code amount} 对象下行）。
 * <p>
 * createdAt 为下单时间（ISO-8601 字符串，装载路径由
 * MasterOrderConvertor 回填 master_order.create_time——FavoriteItem
 * createTime 先例形态；新建未落库路径为 null，不进入读面）。
 *
 * @param masterOrderId  主订单 ID
 * @param orderNo        主订单号（TD-13：ORD + 日期 + snowflake 后段）
 * @param status         主单整体状态（派生枚举 9 值，枚举名序列化）
 * @param createdAt      下单时间（ISO-8601 字符串）
 * @param address        收货地址快照（六段，收件人字段名 receiverName）
 * @param goodsAmount    商品金额（分）
 * @param freightAmount  运费（分）
 * @param discount       优惠（分，一期恒 0）
 * @param paidAmount     实付（分）
 * @param subOrders      子订单列表（按店铺拆单，保持创建序）
 * @param payment        待支付支付单（主单待支付时非空；其余为 null）
 * @author nona9961
 */
public record OrderView(
        Long masterOrderId,
        String orderNo,
        MallOrderStatus status,
        String createdAt,
        OrderAddress address,
        long goodsAmount,
        long freightAmount,
        long discount,
        long paidAmount,
        List<SubOrderView> subOrders,
        PaymentView payment
) {
}