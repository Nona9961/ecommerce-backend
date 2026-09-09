package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.EstimateRequest;
import com.nona.api.mall.EstimateResult;
import com.nona.api.mall.InitiatePaymentRequest;
import com.nona.api.mall.InitiatePaymentResult;
import com.nona.api.mall.MallOrderStatus;
import com.nona.api.mall.OrderResult;
import com.nona.api.mall.OrderView;
import com.nona.api.mall.PlaceOrderRequest;
import com.nona.api.mall.RefundApplyRequest;
import com.nona.api.mall.RefundStatus;
import com.nona.api.mall.RefundView;
import com.nona.api.mall.TradingApi;
import com.nona.api.mall.WaybillView;
import com.nona.application.mall.BuyerOrderQuery;
import com.nona.application.mall.CancelOrderUseCase;
import com.nona.application.mall.ConfirmReceiptUseCase;
import com.nona.application.mall.PaymentUseCase;
import com.nona.application.mall.PlaceOrderUseCase;
import com.nona.application.mall.RefundUseCase;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.domain.payment.ports.PaymentAcquireView;
import com.nona.domain.payment.ports.RefundOrderView;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家交易 REST 控制器（路由前缀 /mall：estimate/orders/payments/
 * sub-orders/refunds，仅买家角色可访问）——WU-59 冻结端点契约，
 * 形状钉死前端 WU-44 约定清单（tradingApi.ts 10 端点逐名对应）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托用例，不承载业务逻辑；
 * 当前买家账号 ID 从跟踪上下文取（认证过滤器已填充，买家维度由此
 * 锚定——TenantContextAccessor 先例 AddressController 同构）。
 * <p>
 * 委托面（绿阶段接线完成）：下单/取消/支付/确认收货/退款端点委托
 * 既有用例；列表/详情/运单委托 {@link BuyerOrderQuery}；发起支付返回
 * 受理视图投影（InitiatePaymentResult 7 字段 = PaymentAcquireView 8
 * 字段去 payTimeoutMillis 规则回显）；退款视图投影（RefundOrderView →
 * RefundView，状态枚举逐名映射）。
 *
 * @author nona9961
 */
@RestController
public class TradingController implements TradingApi {

    /**
     * 下单用例（试算/提交订单）
     */
    private final PlaceOrderUseCase placeOrderUseCase;

    /**
     * 取消订单用例
     */
    private final CancelOrderUseCase cancelOrderUseCase;

    /**
     * 发起支付用例
     */
    private final PaymentUseCase paymentUseCase;

    /**
     * 确认收货用例
     */
    private final ConfirmReceiptUseCase confirmReceiptUseCase;

    /**
     * 退款用例（申请/重试）
     */
    private final RefundUseCase refundUseCase;

    /**
     * 买家订单查询用例（列表/详情/运单）
     */
    private final BuyerOrderQuery buyerOrderQuery;

    /**
     * 请求上下文（取当前登录买家 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造买家交易控制器。
     *
     * @param placeOrderUseCase     下单用例
     * @param cancelOrderUseCase    取消订单用例
     * @param paymentUseCase        发起支付用例
     * @param confirmReceiptUseCase 确认收货用例
     * @param refundUseCase         退款用例
     * @param buyerOrderQuery       买家订单查询用例
     * @param tenantContextAccessor 请求上下文
     */
    public TradingController(PlaceOrderUseCase placeOrderUseCase,
                             CancelOrderUseCase cancelOrderUseCase,
                             PaymentUseCase paymentUseCase,
                             ConfirmReceiptUseCase confirmReceiptUseCase,
                             RefundUseCase refundUseCase,
                             BuyerOrderQuery buyerOrderQuery,
                             TenantContextAccessor tenantContextAccessor) {
        this.placeOrderUseCase = placeOrderUseCase;
        this.cancelOrderUseCase = cancelOrderUseCase;
        this.paymentUseCase = paymentUseCase;
        this.confirmReceiptUseCase = confirmReceiptUseCase;
        this.refundUseCase = refundUseCase;
        this.buyerOrderQuery = buyerOrderQuery;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}——结算试算（委托用例）。
     */
    @Override
    @PostMapping("/mall/estimate")
    public HttpResponse<EstimateResult> estimate(@Valid @RequestBody EstimateRequest request) {
        return HttpResponse.ok(placeOrderUseCase.estimate(currentAccountId(), request));
    }

    /**
     * {@inheritDoc}——提交订单（委托用例）。
     */
    @Override
    @PostMapping("/mall/orders")
    public HttpResponse<OrderResult> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return HttpResponse.ok(placeOrderUseCase.placeOrder(currentAccountId(), request));
    }

    /**
     * {@inheritDoc}——订单列表（状态 tab 解析 + 分页委托查询用例）。
     */
    @Override
    @GetMapping("/mall/orders")
    public HttpResponse<PageResult<OrderView>> listOrders(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        final MallOrderStatus tab = status == null || status.isBlank()
                ? null : MallOrderStatus.fromName(status);
        return HttpResponse.ok(buyerOrderQuery.listPaged(currentAccountId(), tab,
                new PageQuery(pageNum, pageSize)));
    }

    /**
     * {@inheritDoc}——订单详情（委托查询用例；404 按不存在呈现）。
     */
    @Override
    @GetMapping("/mall/orders/{masterOrderId}")
    public HttpResponse<OrderView> getOrder(@PathVariable("masterOrderId") Long masterOrderId) {
        return HttpResponse.ok(buyerOrderQuery.detail(currentAccountId(), masterOrderId));
    }

    /**
     * {@inheritDoc}——取消订单（委托用例；无请求体）。
     */
    @Override
    @PostMapping("/mall/orders/{masterOrderId}/cancel")
    public HttpResponse<Void> cancelOrder(@PathVariable("masterOrderId") Long masterOrderId) {
        cancelOrderUseCase.cancelByBuyer(currentAccountId(), masterOrderId, null);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}——发起支付（受理视图 8 字段 → 对外 7 字段投影；
     * payTimeoutMillis 规则回显不对外）。
     */
    @Override
    @PostMapping("/mall/payments")
    public HttpResponse<InitiatePaymentResult> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request) {
        final PaymentAcquireView view = paymentUseCase.initiatePaymentWithView(
                currentAccountId(), request.masterOrderId());
        return HttpResponse.ok(new InitiatePaymentResult(view.accepted(),
                view.paymentOrderId(), view.payNo(), view.amount(),
                view.timeoutAt() == null ? null : view.timeoutAt().toString(),
                view.channelTxnNo(), view.cashierToken()));
    }

    /**
     * {@inheritDoc}——确认收货（委托用例；无请求体）。
     */
    @Override
    @PostMapping("/mall/sub-orders/{subOrderId}/confirm-receipt")
    public HttpResponse<Void> confirmReceipt(@PathVariable("subOrderId") Long subOrderId) {
        confirmReceiptUseCase.confirmByBuyer(currentAccountId(), subOrderId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}——申请退款（委托用例 + 域视图投影）。
     */
    @Override
    @PostMapping("/mall/sub-orders/{subOrderId}/refunds")
    public HttpResponse<RefundView> applyRefund(
            @PathVariable("subOrderId") Long subOrderId,
            @Valid @RequestBody RefundApplyRequest request) {
        return HttpResponse.ok(toRefundView(refundUseCase.applyRefundByBuyer(
                currentAccountId(), subOrderId, request.reason())));
    }

    /**
     * {@inheritDoc}——退款失败重试（委托用例 + 域视图投影）。
     */
    @Override
    @PostMapping("/mall/refunds/{refundOrderId}/retry")
    public HttpResponse<RefundView> retryRefund(@PathVariable("refundOrderId") Long refundOrderId) {
        return HttpResponse.ok(toRefundView(
                refundUseCase.retryRefundByBuyer(currentAccountId(), refundOrderId)));
    }

    /**
     * {@inheritDoc}——物流跟踪（委托查询用例）。
     */
    @Override
    @GetMapping("/mall/sub-orders/{subOrderId}/waybill")
    public HttpResponse<WaybillView> getWaybill(@PathVariable("subOrderId") Long subOrderId) {
        return HttpResponse.ok(buyerOrderQuery.waybill(currentAccountId(), subOrderId));
    }

    /**
     * 退款单域视图 → 退款单线上契约视图（4 字段投影 + 状态枚举逐名
     * 映射；FAILED 可重试语义不改变）。
     *
     * @param view 退款单域视图
     * @return 线上契约视图
     */
    private static RefundView toRefundView(RefundOrderView view) {
        return new RefundView(view.refundOrderId(), view.refundNo(), view.amount(),
                toRefundStatus(view.status()), view.payNo());
    }

    /**
     * 退款单状态 → 线上契约枚举（3 值逐名对应）。
     *
     * @param status 退款单状态
     * @return 线上契约枚举
     */
    private static RefundStatus toRefundStatus(RefundOrderStatus status) {
        return switch (status) {
            case PENDING -> RefundStatus.PENDING;
            case SUCCEEDED -> RefundStatus.SUCCEEDED;
            case FAILED -> RefundStatus.FAILED;
        };
    }

    /**
     * 当前登录买家账号 ID（认证过滤器写入跟踪作用域的身份）。
     *
     * @return 买家账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}