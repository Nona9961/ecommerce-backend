package com.nona.api.seller;

/**
 * 订单项快照响应体（商家订单详情内嵌，对齐 OrderItem 实体：价格/名称/
 * 图片/规格快照冻结，禁改；金额一律分）。
 *
 * @param productId   商品 ID（快照溯源锚点）
 * @param skuId       SKU ID（库存/退款维度锚点）
 * @param productName 商品名快照（防改价错乱）
 * @param unitPrice   快照单价（分）
 * @param quantity    数量
 * @param subtotal    小计（分 = 单价 × 数量，快照列）
 * @param mainImageUrl 主图 URL 快照（可空）
 * @param specSummary SKU 规格摘要（如 "颜色:黑,尺码:M"；无规格 SKU 可空）
 * @author nona9961
 */
public record SellerOrderItemView(
        Long productId,
        Long skuId,
        String productName,
        long unitPrice,
        int quantity,
        long subtotal,
        String mainImageUrl,
        String specSummary
) {
}