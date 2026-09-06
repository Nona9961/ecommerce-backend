package com.nona.api.mall;

import java.util.List;

/**
 * 下单请求（买家端契约）：提交订单的条目与地址选择。
 * <p>
 * 条目语义（B7.1/B7.2 统一）：下单条目以<b>购物车勾选集</b>为服务端权威
 * 来源——本请求的 {@code skuIds} 是对勾选集的过滤/选择（未勾选项不进
 * 订单，B7.2② 服务端强制）；数量与商品取自购物车条目（结算页数量不可
 * 改、回购物车改，M07）。立即购买（B7.1）由前端先行加购（默认勾选）
 * 后走同一提交路径，SKU/数量沿用页面选择值。
 * <p>
 * 地址（B7.3）：addressId 必填——下单地址必须显式选择（结算页默认地址
 * 预选/可切换）；归属校验（地址属于当前买家）由下单用例承载，他人地址
 * 拒绝。
 *
 * @param addressId 收货地址 ID（必填，须属于当前买家地址簿）
 * @param skuIds    要结算的购物车勾选条目 SKU 集合（非空；须全部处于
 *                  勾选状态，未勾选项拒绝）
 * @author nona9961
 */
public record PlaceOrderRequest(Long addressId, List<Long> skuIds) {
}