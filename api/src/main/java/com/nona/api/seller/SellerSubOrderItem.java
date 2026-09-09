package com.nona.api.seller;

/**
 * 本店子订单列表行响应体（商家订单列表 tab + 分页的返回行，WU-47
 * 约定形状：GET /seller/orders?status=&amp;pageNum=&amp;pageSize=）。
 * <p>
 * 形态契约：行单元 = SubOrder 聚合（店铺维度履约单元）；status 为
 * 履约状态枚举名（8 值全量透传，列表行筛选面）；金额一律分（后端
 * 纪律，前端 api-client 层换算为元）；createTime 为下单时间
 * （主表审计时间，ISO 8601 字符串——LocalDateTime.toString()，
 * FavoriteItem 先例，无时区语义）。
 *
 * @param subOrderId    子订单 ID（列表行点击 → 详情入口）
 * @param subOrderNo    子订单号（唯一业务单号）
 * @param masterOrderId 归属主订单 ID
 * @param status        履约状态（SubOrderStatus 枚举名）
 * @param recipient     收货人姓名快照（买家摘要；脱敏在展示层）
 * @param goodsAmount   商品总额（分）
 * @param freightAmount 运费（分）
 * @param paidAmount    实付金额（分）
 * @param itemCount     订单项数量（商品行数）
 * @param firstImageUrl 首项商品主图 URL（列表缩略；无图为 null）
 * @param createTime    下单时间（ISO 8601）
 * @author nona9961
 */
public record SellerSubOrderItem(
        Long subOrderId,
        String subOrderNo,
        Long masterOrderId,
        String status,
        String recipient,
        long goodsAmount,
        long freightAmount,
        long paidAmount,
        int itemCount,
        String firstImageUrl,
        String createTime
) {
}