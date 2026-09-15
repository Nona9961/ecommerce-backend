package com.nona.api.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageResult;

/**
 * 买家交易契约（/mall/estimate、/mall/orders、/mall/payments、
 * /mall/sub-orders、/mall/refunds，BUYER 角色）——冻结：
 * 端点形态（路径/方法/参数/响应形状）由前端约定钉死
 * （tradingApi.ts 10 端点逐名对应），后端按同形状接入，字段不再演进。
 * <p>
 * 请求/响应语义：
 * <ul>
 *     <li><b>金额纪律</b>：后端一律分（wire 形态），api-client 层换算
 *         为元下行/上行 ×100；</li>
 *     <li><b>当前买家身份</b>：从认证上下文（JWT uid）取，不来自请求
 *         体——服务端实现位于 server 模块 web 层，控制器薄壳委托用例；</li>
 *     <li><b>归属 fail-closed</b>：目标订单/子单/退款单不存在或归属买家
 *         不符统一按不存在呈现（404，order.master_not_found /
 *         order.sub_not_found / payment.refund_not_found /
 *         logistics.not_found），不泄露存在性；</li>
 *     <li><b>列表状态 tab</b>：status 为前端 ORDER_TABS 枚举名（非法值
 *         400 fail-closed）；不传 = 全部（不过滤）。</li>
 * </ul>
 *
 * @author nona9961
 */
public interface TradingApi {

    /**
     * 结算试算（B7.4/O2）：按店铺分组 + 金额明细；纯读展示，不落库。
     *
     * @param request 结算 SKU 集合（须处于购物车勾选状态）
     * @return 试算结果（店铺分组 + 合计金额，分）
     */
    HttpResponse<EstimateResult> estimate(EstimateRequest request);

    /**
     * 提交订单（B7.2）：地址 + 购物车勾选条目过滤集 → 主单/子单/支付单。
     *
     * @param request 下单请求（addressId + skuIds）
     * @return 下单结果（主单/子单/待支付支付单，分）
     */
    HttpResponse<OrderResult> placeOrder(PlaceOrderRequest request);

    /**
     * 订单列表（B9.1 前端约定：GET /mall/orders，状态 tab + 分页）。
     *
     * @param status  状态 tab（MallOrderStatus 枚举名；null = 全部不过滤；
     *                非法值 400）
     * @param pageNum 页码，从 1 开始（PageQuery 归一化）
     * @param pageSize 每页条数（默认 10，上限 100；归一化同左）
     * @return 分页订单视图（创建时间倒序）
     */
    HttpResponse<PageResult<OrderView>> listOrders(String status, int pageNum, int pageSize);

    /**
     * 订单详情（B9.2 前端约定：GET /mall/orders/{masterOrderId}；
     * 404 按不存在呈现——不存在或归属买家不符）。
     *
     * @param masterOrderId 主订单 ID
     * @return 订单视图（含子单/金额/地址/待支付支付单）
     */
    HttpResponse<OrderView> getOrder(Long masterOrderId);

    /**
     * 取消订单（B8.6① 前端约定：POST /mall/orders/{masterOrderId}/cancel；
     * 无请求体）。
     *
     * @param masterOrderId 主订单 ID
     * @return 成功响应
     */
    HttpResponse<Void> cancelOrder(Long masterOrderId);

    /**
     * 发起支付（B8.1 前端约定：POST /mall/payments；受理 ≠ 支付结果，
     * 结果经回调异步到达）。
     *
     * @param request 发起支付请求（masterOrderId）
     * @return 发起支付结果（受理视图 7 字段，分 + ISO-8601 时间）
     */
    HttpResponse<InitiatePaymentResult> initiatePayment(InitiatePaymentRequest request);

    /**
     * 确认收货（B9.3 前端约定：POST /mall/sub-orders/{subOrderId}/confirm-receipt；
     * 无请求体）。
     *
     * @param subOrderId 子订单 ID
     * @return 成功响应
     */
    HttpResponse<Void> confirmReceipt(Long subOrderId);

    /**
     * 申请退款（B8.4 前端约定：POST /mall/sub-orders/{subOrderId}/refunds）。
     *
     * @param subOrderId 子订单 ID
     * @param request    退款申请（reason 可空）
     * @return 退款单视图（分）
     */
    HttpResponse<RefundView> applyRefund(Long subOrderId, RefundApplyRequest request);

    /**
     * 退款失败重试（B8.5 前端约定：POST /mall/refunds/{refundOrderId}/retry；
     * 无请求体）。
     *
     * @param refundOrderId 退款单 ID
     * @return 退款单视图（分；FAILED 单重试复用同一 refundNo）
     */
    HttpResponse<RefundView> retryRefund(Long refundOrderId);

    /**
     * 物流跟踪（B10.1 前端约定：GET /mall/sub-orders/{subOrderId}/waybill；
     * 关联商品行由后端装配）。
     *
     * @param subOrderId 子订单 ID
     * @return 运单视图（含轨迹追加记录与子单商品行）
     */
    HttpResponse<WaybillView> getWaybill(Long subOrderId);
}