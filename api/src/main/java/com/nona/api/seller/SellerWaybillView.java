package com.nona.api.seller;

/**
 * 运单概要响应体（商家订单详情内嵌，已发货后回显；对齐 logistics 域
 * Waybill 聚合展示面：公司/单号发货时定型、物流状态只读）。
 *
 * @param company   承运公司（发货时定型）
 * @param trackingNo 运单号（发货时定型）
 * @param status    运单状态（WaybillStatus 枚举名，如 IN_TRANSIT）
 * @author nona9961
 */
public record SellerWaybillView(
        String company,
        String trackingNo,
        String status
) {
}