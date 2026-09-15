package com.nona.api.mall;

import java.util.Map;

/**
 * 订单项视图（订单展示行，形状钉死前端
 * mall-trading.types.ts {@code OrderItemView}，与 OrderItem 实体快照
 * 逐字段对应；订单列表/详情/运单三个消费面共用）。
 * <p>
 * 金额纪律：后端一律分（unitPrice/subtotal 线上单位为分，api-client
 * 层换算为元下行）。
 *
 * @param productId       商品 ID（快照溯源锚点）
 * @param skuId           SKU ID（库存/退款维度锚点）
 * @param productName     商品名快照
 * @param unitPrice       快照单价（分，非负）
 * @param quantity        数量（正数）
 * @param subtotal        小计（分，单价 × 数量）
 * @param mainImageUrl    主图 URL 快照（可空防御形态）
 * @param specSummary     SKU 规格摘要（如「黑色 / XL」；无规格 SKU 可空）
 * @param specAttributes  规格键值对快照（保序）
 * @param customAttributes 自定义属性快照（保序）
 * @author nona9961
 */
public record OrderItemView(
        Long productId,
        Long skuId,
        String productName,
        long unitPrice,
        int quantity,
        long subtotal,
        String mainImageUrl,
        String specSummary,
        Map<String, String> specAttributes,
        Map<String, String> customAttributes
) {
}