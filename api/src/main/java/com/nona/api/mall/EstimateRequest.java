package com.nona.api.mall;

import java.util.List;

/**
 * 结算试算请求（买家端契约，B7.4 运费试算）：结算页按店铺分组展示
 * 商品清单/运费/金额明细的数据源。
 * <p>
 * 与下单同源（{@link PlaceOrderRequest}）：条目取自购物车勾选集，
 * skuIds 为选择集过滤（结算页展示当前勾选条目）；数量与商品取自购物车
 * 条目（数量不可改、回购物车改，M07）。试算为纯读展示动作，不落库、
 * 不开写事务；立即购买场景由前端先行加购（默认勾选）进入同一试算路径。
 *
 * @param skuIds 要试算的购物车勾选条目 SKU 集合（非空；须全部处于勾选
 *               状态——未勾选/不在购物车的条目拒绝，与下单校验一致）
 * @author nona9961
 */
public record EstimateRequest(List<Long> skuIds) {
}