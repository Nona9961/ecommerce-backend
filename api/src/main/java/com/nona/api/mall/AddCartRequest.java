package com.nona.api.mall;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 加购请求体（POST /mall/cart）：把指定商品（SKU）加入购物车。
 * <p>
 * SKU 需要与商品一起显式传入（服务端按商品校验在售与 SKU 归属，可售上限
 * 从买家商品视图读取）；同 SKU 重复加购数量累加（购物车同 SKU 单条目语义）。
 *
 * @param productId 商品 ID（在售校验与 SKU 归属校验的锚点）
 * @param skuId     SKU ID（库存/价格维度的可售单元）
 * @param quantity  加购数量（必须为正；累加后在可售上限内）
 *
 * @author nona9961
 */
public record AddCartRequest(
        @NotNull Long productId,
        @NotNull @Positive Long skuId,
        @NotNull @Positive Integer quantity
) {
}