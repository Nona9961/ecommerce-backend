package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageResult;
import com.nona.api.mall.EstimateRequest;
import com.nona.api.mall.EstimateResult;
import com.nona.api.mall.InitiatePaymentRequest;
import com.nona.api.mall.InitiatePaymentResult;
import com.nona.api.mall.OrderResult;
import com.nona.api.mall.OrderView;
import com.nona.api.mall.PlaceOrderRequest;
import com.nona.api.mall.RefundApplyRequest;
import com.nona.api.mall.RefundView;
import com.nona.api.mall.TradingApi;
import com.nona.api.mall.WaybillView;
import com.nona.application.mall.BuyerOrderQuery;
import com.nona.application.mall.CancelOrderUseCase;
import com.nona.application.mall.ConfirmReceiptUseCase;
import com.nona.application.mall.PaymentUseCase;
import com.nona.application.mall.PlaceOrderUseCase;
import com.nona.application.mall.RefundUseCase;
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
 * 红阶段状态：方法体为 {@link UnsupportedOperationException} 契约占位
 * （端点路由/参数绑定/返回类型冻结；绿阶段实现为用例委托——方法体
 * 逐方法替换，注解与签名零改动）。
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
     * {@inheritDoc}——结算试算；
     * 红阶段契约占位，绿阶段：{@code placeOrderUseCase.estimate(buyerId, request)}。
     */
    @Override
    @PostMapping("/mall/estimate")
    public HttpResponse<EstimateResult> estimate(@Valid @RequestBody EstimateRequest request) {
        throw new UnsupportedOperationException(
                "estimate 端点未实现：红阶段契约占位，绿阶段委托 placeOrderUseCase.estimate");
    }

    /**
     * {@inheritDoc}——提交订单；
     * 红阶段契约占位，绿阶段：{@code placeOrderUseCase.placeOrder(buyerId, request)}。
     */
    @Override
    @PostMapping("/mall/orders")
    public HttpResponse<OrderResult> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        throw new UnsupportedOperationException(
                "placeOrder 端点未实现：红阶段契约占位，绿阶段委托 placeOrderUseCase.placeOrder");
    }

    /**
     * {@inheritDoc}——订单列表；
     * 红阶段契约占位，绿阶段：{@code buyerOrderQuery.listPaged(buyerId, tab, query)}。
     */
    @Override
    @GetMapping("/mall/orders")
    public HttpResponse<PageResult<OrderView>> listOrders(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        throw new UnsupportedOperationException(
                "listOrders 端点未实现：红阶段契约占位，绿阶段委托 buyerOrderQuery.listPaged");
    }

    /**
     * {@inheritDoc}——订单详情；
     * 红阶段契约占位，绿阶段：{@code buyerOrderQuery.detail(buyerId, masterOrderId)}。
     */
    @Override
    @GetMapping("/mall/orders/{masterOrderId}")
    public HttpResponse<OrderView> getOrder(@PathVariable("masterOrderId") Long masterOrderId) {
        throw new UnsupportedOperationException(
                "getOrder 端点未实现：红阶段契约占位，绿阶段委托 buyerOrderQuery.detail");
    }

    /**
     * {@inheritDoc}——取消订单；
     * 红阶段契约占位，绿阶段：{@code cancelOrderUseCase.cancelByBuyer(buyerId, id, null)}。
     */
    @Override
    @PostMapping("/mall/orders/{masterOrderId}/cancel")
    public HttpResponse<Void> cancelOrder(@PathVariable("masterOrderId") Long masterOrderId) {
        throw new UnsupportedOperationException(
                "cancelOrder 端点未实现：红阶段契约占位，绿阶段委托 cancelOrderUseCase.cancelByBuyer");
    }

    /**
     * {@inheritDoc}——发起支付；
     * 红阶段契约占位，绿阶段：{@code paymentUseCase.initiatePaymentWithView(buyerId, id)}。
     */
    @Override
    @PostMapping("/mall/payments")
    public HttpResponse<InitiatePaymentResult> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request) {
        throw new UnsupportedOperationException(
                "initiatePayment 端点未实现：红阶段契约占位，绿阶段委托 paymentUseCase.initiatePaymentWithView");
    }

    /**
     * {@inheritDoc}——确认收货；
     * 红阶段契约占位，绿阶段：{@code confirmReceiptUseCase.confirmByBuyer(buyerId, subOrderId)}。
     */
    @Override
    @PostMapping("/mall/sub-orders/{subOrderId}/confirm-receipt")
    public HttpResponse<Void> confirmReceipt(@PathVariable("subOrderId") Long subOrderId) {
        throw new UnsupportedOperationException(
                "confirmReceipt 端点未实现：红阶段契约占位，绿阶段委托 confirmReceiptUseCase.confirmByBuyer");
    }

    /**
     * {@inheritDoc}——申请退款；
     * 红阶段契约占位，绿阶段：{@code refundUseCase.applyRefundByBuyer(...)}。
     */
    @Override
    @PostMapping("/mall/sub-orders/{subOrderId}/refunds")
    public HttpResponse<RefundView> applyRefund(
            @PathVariable("subOrderId") Long subOrderId,
            @Valid @RequestBody RefundApplyRequest request) {
        throw new UnsupportedOperationException(
                "applyRefund 端点未实现：红阶段契约占位，绿阶段委托 refundUseCase.applyRefundByBuyer");
    }

    /**
     * {@inheritDoc}——退款失败重试；
     * 红阶段契约占位，绿阶段：{@code refundUseCase.retryRefundByBuyer(...)}。
     */
    @Override
    @PostMapping("/mall/refunds/{refundOrderId}/retry")
    public HttpResponse<RefundView> retryRefund(@PathVariable("refundOrderId") Long refundOrderId) {
        throw new UnsupportedOperationException(
                "retryRefund 端点未实现：红阶段契约占位，绿阶段委托 refundUseCase.retryRefundByBuyer");
    }

    /**
     * {@inheritDoc}——物流跟踪；
     * 红阶段契约占位，绿阶段：{@code buyerOrderQuery.waybill(buyerId, subOrderId)}。
     */
    @Override
    @GetMapping("/mall/sub-orders/{subOrderId}/waybill")
    public HttpResponse<WaybillView> getWaybill(@PathVariable("subOrderId") Long subOrderId) {
        throw new UnsupportedOperationException(
                "getWaybill 端点未实现：红阶段契约占位，绿阶段委托 buyerOrderQuery.waybill");
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