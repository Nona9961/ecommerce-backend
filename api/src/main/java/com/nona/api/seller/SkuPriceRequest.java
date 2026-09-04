package com.nona.api.seller;

import jakarta.validation.constraints.Positive;

/**
 * 商品 SKU 改价请求体。
 * <p>
 * 价格语义：null=清除价格复位未定价（草稿期合法形态——未定价不参与
 * 出售）；非空必须为正整数分（0 与负数无业务意义，格式校验拒绝，
 * 领域聚合守卫双保险）。目标 SKU 必须属于请求商品，否则 404。
 *
 * @param price 新价格（分；null=清除价格复位未定价）
 * @author nona9961
 */
public record SkuPriceRequest(
        @Positive(message = "SKU价格必须为正整数") Long price
) {
}