package com.nona.api.seller;

/**
 * 金额明细响应体（商家订单详情内嵌，对齐 AmountDetail 实体：商品总额/
 * 运费/优惠/实付；金额一律分，前端 api-client 层换算为元）。
 *
 * @param goodsAmount  商品总额（分）
 * @param freightAmount 运费（分）
 * @param discount     优惠金额（分）
 * @param paidAmount   实付金额（分）
 * @author nona9961
 */
public record SellerOrderAmount(
        long goodsAmount,
        long freightAmount,
        long discount,
        long paidAmount
) {
}