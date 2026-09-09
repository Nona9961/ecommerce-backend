package com.nona.web.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.auth.Portal;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.EstimateResult;
import com.nona.api.mall.EstimateResult.EstimateGroup;
import com.nona.api.mall.EstimateResult.EstimateItem;
import com.nona.api.mall.MallOrderStatus;
import com.nona.api.mall.MallWaybillStatus;
import com.nona.api.mall.OrderAddress;
import com.nona.api.mall.OrderItemView;
import com.nona.api.mall.OrderResult;
import com.nona.api.mall.OrderView;
import com.nona.api.mall.PaymentStatus;
import com.nona.api.mall.PaymentView;
import com.nona.api.mall.RefundStatus;
import com.nona.api.mall.RefundView;
import com.nona.api.mall.SubOrderView;
import com.nona.api.mall.WaybillTrackView;
import com.nona.api.mall.WaybillView;
import com.nona.application.mall.BuyerOrderQuery;
import com.nona.application.mall.CancelOrderUseCase;
import com.nona.application.mall.ConfirmReceiptUseCase;
import com.nona.application.mall.PaymentUseCase;
import com.nona.application.mall.PlaceOrderUseCase;
import com.nona.application.mall.RefundUseCase;
import com.nona.domain.payment.ports.PaymentAcquireView;
import com.nona.domain.payment.ports.RefundOrderView;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.persistence.repository.jpa.AccountJpaRepository;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.JwtTokenProvider;
import com.nona.util.JacksonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 买家交易端点契约测试（WU-59：TradingApi 10 端点——路径/方法/参数
 * 绑定/响应形状，与前端 tradingApi.ts 约定逐字段锁定）。
 * <p>
 * 装配策略：全部交易用例 bean 以 {@code @MockitoBean} 替换为 mock
 * （容器仅验证路由/MVC 装配与形状投影，不触碰真库——真链归
 * BuyerOrderQueryAcTest + walkthrough）；认证沿用
 * AddressBookApiIntegrationAcTest 先例（注册落库 + 真实 JWT + 缓存
 * miss 回填真实状态）。
 * <p>
 * 红阶段状态：controller 方法体为 UOE 契约占位——全部用例红（500
 * generic 兜底）；绿阶段实现 controller 委托后按本矩阵转绿（mock 形
 * 状不变，断言面不变）。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class TradingApiContractAcTest {

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 账号 JPA 仓储（测试数据清理）
     */
    @Autowired
    private AccountJpaRepository accountRepository;

    /**
     * JWT 签发器（构造买家访问令牌）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private final ObjectMapper objectMapper = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 买家订单查询用例（mock——形状投影断言面）
     */
    @MockitoBean
    private BuyerOrderQuery buyerOrderQuery;

    /**
     * 下单用例（mock——试算/下单端点形状投影面）
     */
    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    /**
     * 取消订单用例（mock——void 端点成功面）
     */
    @MockitoBean
    private CancelOrderUseCase cancelOrderUseCase;

    /**
     * 确认收货用例（mock——void 端点成功面）
     */
    @MockitoBean
    private ConfirmReceiptUseCase confirmReceiptUseCase;

    /**
     * 退款用例（mock——退款视图投影面）
     */
    @MockitoBean
    private RefundUseCase refundUseCase;

    /**
     * 发起支付用例（mock——受理视图投影面）
     */
    @MockitoBean
    private PaymentUseCase paymentUseCase;

    /**
     * 每用例前清理账号数据并 stub 缓存 miss（过滤器走真实账号状态回填）。
     */
    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        when(authUserCache.get(anyLong())).thenReturn(java.util.Optional.empty());
    }

    /* ------------------------------------------------------------------ */
    /* 形状基线（api DTO record 冻结形态；字段名与前端约定逐字段锁定）     */
    /* ------------------------------------------------------------------ */

    /**
     * 订单项形状基线（金额分：单价 5000、数量 1、小计 5000）。
     */
    private static OrderItemView orderItem() {
        return new OrderItemView(900L, 800L, "测试商品", 5000L, 1, 5000L,
                "http://img.example/cover.jpg", "颜色:黑,尺码:M",
                Map.of("颜色", "黑"), Map.of("产地", "中国"));
    }

    /**
     * 子单形状基线。
     */
    private static SubOrderView subOrderView() {
        return new SubOrderView(10L, "SUB000010", 5001L, "店铺甲", MallOrderStatus.PAID,
                null, 5000L, 300L, 0L, 5300L, List.of(orderItem()));
    }

    /**
     * 支付单形状基线（待支付）。
     */
    private static PaymentView paymentView() {
        return new PaymentView(700L, "PAY000001", 5300L,
                Instant.now().plusSeconds(1800).toString(), PaymentStatus.PENDING_PAYMENT);
    }

    /**
     * 订单视图形状基线（主单 PAID——非待支付，payment null）。
     */
    private static OrderView orderView() {
        return new OrderView(100L, "ORD000100", MallOrderStatus.PAID,
                Instant.now().minusSeconds(3600).toString(),
                new OrderAddress("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号"),
                5000L, 300L, 0L, 5300L, List.of(subOrderView()), null);
    }

    /**
     * 运单视图形状基线。
     */
    private static WaybillView waybillView() {
        return new WaybillView(800L, 10L, "测试物流", "SF000000001", MallWaybillStatus.SHIPPED,
                List.of(new WaybillTrackView(MallWaybillStatus.SHIPPED,
                        Instant.now().minusSeconds(600).toString(), "包裹已揽收")),
                "SUB000010", 5001L, "店铺甲", List.of(orderItem()));
    }

    /**
     * 受理视图形状基线（PendingPayment + AcquireResult 融合 8 字段）。
     */
    private static PaymentAcquireView acquireView() {
        return new PaymentAcquireView(700L, "PAY000001", 5300L, 1_800_000L,
                Instant.now().plusSeconds(1800), true, "CHANNEL-TXN-001", "mock-cashier:demo");
    }

    /**
     * 退款单视图形状基线（domain 投影：FAILED 可重试）。
     */
    private static RefundOrderView refundOrderView() {
        return new RefundOrderView(900L, "REF000001", 5300L, RefundOrderStatus.FAILED, "PAY000001");
    }

    /**
     * 试算结果形状基线。
     */
    private static EstimateResult estimateResult() {
        final EstimateGroup group = new EstimateGroup(5001L, "店铺甲", 5000L, 300L, 0L, 5300L,
                List.of(new EstimateItem(900L, 800L, "测试商品", 5000L, 1, 5000L)));
        return new EstimateResult(List.of(group), 5000L, 300L, 0L, 5300L);
    }

    /**
     * 下单结果形状基线。
     */
    private static OrderResult orderResult() {
        return new OrderResult(100L, "ORD000100",
                List.of(new OrderResult.SubOrderResult(10L, "SUB000010", 5001L, "店铺甲",
                        5000L, 300L, 5300L)),
                new OrderResult.PaymentResult(700L, "PAY000001", 5300L,
                        Instant.now().plusSeconds(1800)));
    }

    /* ------------------------------------------------------------------ */
    /* 认证基建（AddressBookApiIntegrationAcTest 先例同构）                */
    /* ------------------------------------------------------------------ */

    /**
     * 注册买家并返回其访问令牌（注册落库，账号状态回填走真实 DB）。
     *
     * @return JWT
     */
    private String registerBuyer() throws Exception {
        final MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer-" + System.nanoTime()
                                + "\",\"password\":\"secret123\",\"portal\":\"MALL\"}"))
                .andExpect(status().isOk())
                .andReturn();
        final long accountId = Long.parseLong(objectMapper.readTree(
                result.getResponse().getContentAsString()).path("data").path("userId").asText());
        return tokenProvider.issueToken(accountId, Portal.MALL);
    }

    /* ------------------------------------------------------------------ */
    /* 端点契约用例（红阶段全红 = controller UOE；绿阶段实现后逐条转绿）   */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("list-1 订单列表形状：PageResult 包裹 + 订单视图全字段（含 address.receiverName）")
    void listOrders_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(buyerOrderQuery.listPaged(anyLong(), any(), any()))
                .thenReturn(PageResult.of(List.of(orderView()), 1L, new PageQuery(1, 10)));

        mockMvc.perform(get("/mall/orders")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "PAID")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.records[0].masterOrderId").value(100))
                .andExpect(jsonPath("$.data.records[0].orderNo").value("ORD000100"))
                .andExpect(jsonPath("$.data.records[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.records[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.records[0].address.receiverName").value("张三"))
                .andExpect(jsonPath("$.data.records[0].address.phone").value("13800000000"))
                .andExpect(jsonPath("$.data.records[0].address.province").value("浙江省"))
                .andExpect(jsonPath("$.data.records[0].address.city").value("杭州市"))
                .andExpect(jsonPath("$.data.records[0].address.district").value("西湖区"))
                .andExpect(jsonPath("$.data.records[0].address.detail").value("文一西路 1 号"))
                .andExpect(jsonPath("$.data.records[0].goodsAmount").value(5000))
                .andExpect(jsonPath("$.data.records[0].freightAmount").value(300))
                .andExpect(jsonPath("$.data.records[0].discount").value(0))
                .andExpect(jsonPath("$.data.records[0].paidAmount").value(5300))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].subOrderId").value(10))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].subOrderNo").value("SUB000010"))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].shopId").value(5001))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].productId").value(900))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].skuId").value(800))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].productName")
                        .value("测试商品"))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].unitPrice").value(5000))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].subtotal").value(5000))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].specSummary")
                        .value("颜色:黑,尺码:M"))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].specAttributes.颜色")
                        .value("黑"))
                .andExpect(jsonPath("$.data.records[0].subOrders[0].items[0].customAttributes.产地")
                        .value("中国"))
                .andExpect(jsonPath("$.data.records[0].payment").value(
                        org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("list-2 非法状态 tab：未知枚举名 → 400 generic.validation_failed（fail-closed）")
    void listOrders_illegalStatus_returns400() throws Exception {
        final String token = registerBuyer();
        when(buyerOrderQuery.listPaged(anyLong(), any(), any()))
                .thenReturn(PageResult.empty());

        mockMvc.perform(get("/mall/orders")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"));
    }

    @Test
    @DisplayName("detail-1 订单详情形状：订单视图全字段（支付单嵌套 + 超时 ISO 字符串）")
    void getOrder_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        final OrderView pendingView = new OrderView(101L, "ORD000101", MallOrderStatus.PENDING_PAYMENT,
                Instant.now().minusSeconds(3600).toString(),
                new OrderAddress("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号"),
                5000L, 300L, 0L, 5300L, List.of(subOrderView()), paymentView());
        when(buyerOrderQuery.detail(anyLong(), eq(101L))).thenReturn(pendingView);

        mockMvc.perform(get("/mall/orders/101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterOrderId").value(101))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.payment.paymentOrderId").value(700))
                .andExpect(jsonPath("$.data.payment.payNo").value("PAY000001"))
                .andExpect(jsonPath("$.data.payment.amount").value(5300))
                .andExpect(jsonPath("$.data.payment.timeoutAt").isNotEmpty())
                .andExpect(jsonPath("$.data.payment.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("detail-2 订单详情不存在/归属不符：404 order.master_not_found（fail-closed）")
    void getOrder_missing_returns404() throws Exception {
        final String token = registerBuyer();
        when(buyerOrderQuery.detail(anyLong(), eq(999L)))
                .thenThrow(new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                        "主订单不存在或不属于当前买家"));

        mockMvc.perform(get("/mall/orders/999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order.master_not_found"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("waybill-1 运单形状：轨迹 + 子单商品行/店铺名装配字段全锁定")
    void getWaybill_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(buyerOrderQuery.waybill(anyLong(), eq(10L))).thenReturn(waybillView());

        mockMvc.perform(get("/mall/sub-orders/10/waybill")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillId").value(800))
                .andExpect(jsonPath("$.data.subOrderId").value(10))
                .andExpect(jsonPath("$.data.company").value("测试物流"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF000000001"))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracks[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.tracks[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.data.tracks[0].description").value("包裹已揽收"))
                .andExpect(jsonPath("$.data.subOrderNo").value("SUB000010"))
                .andExpect(jsonPath("$.data.shopId").value(5001))
                .andExpect(jsonPath("$.data.shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.items[0].skuId").value(800));
    }

    @Test
    @DisplayName("pay-1 发起支付形状：受理视图 7 字段投影（金额分 + ISO 时间 + 收银台标识）")
    void initiatePayment_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(paymentUseCase.initiatePaymentWithView(anyLong(), eq(100L)))
                .thenReturn(acquireView());

        mockMvc.perform(post("/mall/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterOrderId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.paymentOrderId").value(700))
                .andExpect(jsonPath("$.data.payNo").value("PAY000001"))
                .andExpect(jsonPath("$.data.amount").value(5300))
                .andExpect(jsonPath("$.data.timeoutAt").isNotEmpty())
                .andExpect(jsonPath("$.data.channelTxnNo").value("CHANNEL-TXN-001"))
                .andExpect(jsonPath("$.data.cashierToken").value("mock-cashier:demo"));
    }

    @Test
    @DisplayName("estimate-1 试算形状：店铺分组 + 合计金额字段全锁定")
    void estimate_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(placeOrderUseCase.estimate(anyLong(), any())).thenReturn(estimateResult());

        mockMvc.perform(post("/mall/estimate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuIds\":[800]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].shopId").value(5001))
                .andExpect(jsonPath("$.data.groups[0].shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.groups[0].goodsAmount").value(5000))
                .andExpect(jsonPath("$.data.groups[0].freightAmount").value(300))
                .andExpect(jsonPath("$.data.groups[0].discount").value(0))
                .andExpect(jsonPath("$.data.groups[0].paidAmount").value(5300))
                .andExpect(jsonPath("$.data.groups[0].items[0].skuId").value(800))
                .andExpect(jsonPath("$.data.groups[0].items[0].unitPrice").value(5000))
                .andExpect(jsonPath("$.data.groups[0].items[0].subtotal").value(5000))
                .andExpect(jsonPath("$.data.totalGoodsAmount").value(5000))
                .andExpect(jsonPath("$.data.totalFreightAmount").value(300))
                .andExpect(jsonPath("$.data.totalDiscount").value(0))
                .andExpect(jsonPath("$.data.totalPaidAmount").value(5300));
    }

    @Test
    @DisplayName("place-1 下单形状：主单/子单投影 + 待支付支付单全字段")
    void placeOrder_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(placeOrderUseCase.placeOrder(anyLong(), any())).thenReturn(orderResult());

        mockMvc.perform(post("/mall/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":1,\"skuIds\":[800]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.masterOrderId").value(100))
                .andExpect(jsonPath("$.data.orderNo").value("ORD000100"))
                .andExpect(jsonPath("$.data.subOrders[0].subOrderId").value(10))
                .andExpect(jsonPath("$.data.subOrders[0].subOrderNo").value("SUB000010"))
                .andExpect(jsonPath("$.data.subOrders[0].shopId").value(5001))
                .andExpect(jsonPath("$.data.subOrders[0].shopName").value("店铺甲"))
                .andExpect(jsonPath("$.data.subOrders[0].goodsAmount").value(5000))
                .andExpect(jsonPath("$.data.subOrders[0].freightAmount").value(300))
                .andExpect(jsonPath("$.data.subOrders[0].paidAmount").value(5300))
                .andExpect(jsonPath("$.data.payment.paymentOrderId").value(700))
                .andExpect(jsonPath("$.data.payment.payNo").value("PAY000001"))
                .andExpect(jsonPath("$.data.payment.amount").value(5300))
                .andExpect(jsonPath("$.data.payment.timeoutAt").isNotEmpty());
    }

    @Test
    @DisplayName("cancel-1 取消订单：无请求体 POST → 200 成功（用例 void）")
    void cancelOrder_ok() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(post("/mall/orders/100/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("confirm-1 确认收货：无请求体 POST → 200 成功（用例 void）")
    void confirmReceipt_ok() throws Exception {
        final String token = registerBuyer();

        mockMvc.perform(post("/mall/sub-orders/10/confirm-receipt")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("refund-1 申请退款形状：RefundOrderView → RefundView 投影（4 字段）")
    void applyRefund_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(refundUseCase.applyRefundByBuyer(anyLong(), eq(10L), any()))
                .thenReturn(refundOrderView());

        mockMvc.perform(post("/mall/sub-orders/10/refunds")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundOrderId").value(900))
                .andExpect(jsonPath("$.data.refundNo").value("REF000001"))
                .andExpect(jsonPath("$.data.amount").value(5300))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.payNo").value("PAY000001"));
    }

    @Test
    @DisplayName("refund-2 重试退款形状：FAILED 单重试返回同一投影（复用单号）")
    void retryRefund_shape_contractFrozen() throws Exception {
        final String token = registerBuyer();
        when(refundUseCase.retryRefundByBuyer(anyLong(), eq(900L)))
                .thenReturn(refundOrderView());

        mockMvc.perform(post("/mall/refunds/900/retry")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundOrderId").value(900))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    @DisplayName("auth-1 未认证访问 → 401 auth.unauthorized（端点属 /mall/** 保护面，恒绿锚点）")
    void tradingEndpoints_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/mall/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }
}