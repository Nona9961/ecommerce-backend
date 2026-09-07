package com.nona.application.mall;

import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.factory.RefundOrderFactory;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.ports.RefundOrderView;
import com.nona.domain.payment.ports.RefundRequest;
import com.nona.domain.payment.ports.RefundResult;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.domain.payment.repo.RefundOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款编排用例场景测试（B8.4 买家申请 + B9.4② 发货超时 + 失败重试，
 * 红阶段契约）。
 * <p>
 * 覆盖：happy——申请全链（归属 → 防重 → 提权段内建单（金额=子单实付 +
 * 发货快照）→ beginRefund → 渠道受理落位 → 视图返回）与已发货申请
 * 快照、发货超时入口（closeByTimeout → 建单 → 受理）；critical——超时
 * 重扫幂等短路、已有退款单防重、FAILED 重试（同 refundNo 重新受理）；
 * fail——主单不存在/归属不符 404、子单不存在 404、支付单缺失防御拒绝、
 * 受理拒绝（FAILED 落库）、重试状态守卫、不可退状态守卫透传。
 * <p>
 * 依赖装配：全部端口/仓储/渠道/提权/事务以 mock 承载（编排契约断言
 * 面）；提权事务以 mock 直执行（真实事务回滚属应用层注解面，冒烟清单
 * 覆盖）。红阶段失败原因 = 实现缺失（用例方法体 UOE）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class RefundUseCaseUnitTest {

    /**
     * 测试买家 / 主订单 / 子单 / 店铺 / SKU（取消与支付回调测试基线同构）
     */
    private static final long BUYER_ID = 200L;
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long SHOP_A = 4001L;
    private static final long SKU_A1 = 3001L;

    /**
     * 支付单号 / 子单实付（分）
     */
    private static final String PAY_NO = "PAY20260907000001";
    private static final long PAID_AMOUNT = 45800L;

    /**
     * 退款单号 / 渠道流水（断言锚点）
     */
    private static final String REFUND_NO = "REF202609070001";
    private static final String TXN_REFUND_1 = "TXN-REFUND-001";
    private static final String TXN_REFUND_2 = "TXN-REFUND-002";

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private MasterOrderRepository masterOrderRepository;

    @Mock
    private RefundOrderRepository refundOrderRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private OrderFacade orderFacade;

    @Mock
    private PaymentGateway gateway;

    @Mock
    private TenantPrivilege tenantPrivilege;

    @Mock
    private TransactionTemplate transactionTemplate;

    /**
     * 被测用例（红阶段不注册 Spring；依赖全 mock，setUp 装配）。
     */
    private RefundUseCase useCase;

    /**
     * 每用例前重建被测用例（mock 桩在各用例内布置）；提权事务桩统一
     * lenient 化——fail 用例不触达提权段，lenient 豁免
     * UnnecessaryStubbing（取消/支付回调编排用例同款）。
     */
    @BeforeEach
    void setUp() {
        useCase = new RefundUseCase(subOrderRepository, masterOrderRepository,
                refundOrderRepository, paymentOrderRepository, orderFacade, gateway,
                new RefundOrderFactory(), tenantPrivilege, transactionTemplate);
        try {
            lenient().when(tenantPrivilege.elevatedInTransaction(
                            eq(transactionTemplate), any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
    }

    /* ================= fixtures ================= */

    /**
     * 主单基线（归属买家 BUYER_ID，2 子单投影）。
     */
    private static MasterOrder master() {
        return new MasterOrder(MASTER_ID, "ORD202609070001", BUYER_ID,
                address(), amount(45000L, 800L, 45800L),
                List.of(SUB_A), List.of(amount(45000L, 800L, 45800L)),
                MasterOrderStatus.PAID);
    }

    /**
     * 按状态装配合法子单（单 SKU：SKU_A1 × 2，goods 45000 freight 800
     * paid 45800）。
     */
    private static SubOrder subA(SubOrderStatus status) {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(45000L, 800L, 45800L),
                List.of(item(SKU_A1, 22500L, 2)), status, null);
    }

    private static OrderItem item(Long skuId, long price, int qty) {
        return new OrderItem(skuId, skuId, "测试商品" + skuId, price, qty, price * qty,
                null, null, Map.of(), Map.of());
    }

    private static AmountDetail amount(long goods, long freight, long paid) {
        return new AmountDetail(goods, freight, 0L, paid);
    }

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /**
     * 关联主单的已支付支付单（payNo 断言锚点）。
     */
    private static PaymentOrder paymentOf() {
        return new PaymentOrder(9001L, PAY_NO, MASTER_ID, PAID_AMOUNT, "MOCK",
                Instant.parse("2026-09-07T10:30:00Z"));
    }

    /**
     * 既有退款单（防重/重试装载断言锚点；退款中态）。
     */
    private static RefundOrder existingRefund(RefundOrderStatus status, String txnNo) {
        return new RefundOrder(7001L, REFUND_NO, PAY_NO, SUB_A, PAID_AMOUNT,
                false, "不想要了", status, txnNo, List.of());
    }

    /* ================= happy path ================= */

    /**
     * happy-1 买家申请全链（未发货）：归属校验 → 防重（无既有单）→
     * 提权段内建单（金额 = 子单实付 + 未发货快照 + 原因透传）→
     * beginRefund（子单退款中推进）→ 渠道受理（payNo/refundNo/金额
     * 参数断言）→ 受理落位 → 落库 → 视图返回（PENDING）。
     */
    @Test
    @DisplayName("买家申请全链（未发货）：建单 + beginRefund + 受理落位 + 视图返回")
    void applyRefundByBuyer_notShipped_fullChain() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(refundOrderRepository.findBySubOrderId(SUB_A)).thenReturn(null);
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf());
        when(gateway.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResult(true, PAY_NO, REFUND_NO, TXN_REFUND_1));

        final RefundOrderView view = useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "不想要了");

        // 受理参数：支付单号 + 退款单号（建单动态生成，TD-13 REF 单号） + 金额 = 子单实付（B8.5③）
        final org.mockito.ArgumentCaptor<RefundRequest> refundCaptor =
                org.mockito.ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway).refund(refundCaptor.capture());
        assertThat(refundCaptor.getValue().payNo()).isEqualTo(PAY_NO);
        assertThat(refundCaptor.getValue().refundNo()).startsWith("REF");
        assertThat(refundCaptor.getValue().amountCents()).isEqualTo(PAID_AMOUNT);
        verify(orderFacade).beginRefund(SUB_A);
        // 建单定型：金额 = 子单实付 + 未发货快照 + 原因透传（ArgumentCaptor 断言）
        final org.mockito.ArgumentCaptor<RefundOrder> captor =
                org.mockito.ArgumentCaptor.forClass(RefundOrder.class);
        verify(refundOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualTo(PAID_AMOUNT);
        assertThat(captor.getValue().isShippedAtApply()).isFalse();
        assertThat(captor.getValue().getReason()).isEqualTo("不想要了");
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundOrderStatus.PENDING);
        assertThat(view.payNo()).isEqualTo(PAY_NO);
        assertThat(view.amount()).isEqualTo(PAID_AMOUNT);
        assertThat(view.status()).isEqualTo(RefundOrderStatus.PENDING);
    }

    /**
     * happy-2 已发货申请：shippedAtApply 快照 = true（已发货退款不回补
     * 库存——C9 判定锚点固化在申请时刻）。
     */
    @Test
    @DisplayName("已发货申请：已发货快照定型（退款成功不回补）")
    void applyRefundByBuyer_shipped_snapshotTrue() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.SHIPPED));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(refundOrderRepository.findBySubOrderId(SUB_A)).thenReturn(null);
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf());
        when(gateway.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResult(true, PAY_NO, REFUND_NO, TXN_REFUND_1));

        final RefundOrderView view = useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "货不对板");

        final org.mockito.ArgumentCaptor<RefundOrder> captor =
                org.mockito.ArgumentCaptor.forClass(RefundOrder.class);
        verify(refundOrderRepository).save(captor.capture());
        assertThat(captor.getValue().isShippedAtApply()).isTrue();
        assertThat(view.status()).isEqualTo(RefundOrderStatus.PENDING);
        verify(orderFacade).beginRefund(SUB_A);
    }

    /**
     * happy-3 发货超时系统退款（B9.4②）：已支付未发货子单 → closeByTimeout
     * （履约侧已关闭，资金侧由退款单承载）→ 建单（未发货快照固定
     * false——超时关闭前提即货未出）→ 受理落位 → 落库。
     */
    @Test
    @DisplayName("发货超时系统退款：closeByTimeout + 建单（未发货）+ 受理落位")
    void refundByShipTimeout_paidSubOrder_fullChain() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(refundOrderRepository.findBySubOrderId(SUB_A)).thenReturn(null);
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf());
        when(gateway.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResult(true, PAY_NO, REFUND_NO, TXN_REFUND_1));

        final RefundOrderView view = useCase.refundByShipTimeout(SUB_A);

        verify(orderFacade).closeByTimeout(SUB_A);
        verify(orderFacade, never()).beginRefund(anyLong());
        // 受理参数：支付单号 + 退款单号（建单动态生成，TD-13 REF 单号） + 金额 = 子单实付
        final org.mockito.ArgumentCaptor<RefundRequest> refundCaptor =
                org.mockito.ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway).refund(refundCaptor.capture());
        assertThat(refundCaptor.getValue().payNo()).isEqualTo(PAY_NO);
        assertThat(refundCaptor.getValue().refundNo()).startsWith("REF");
        assertThat(refundCaptor.getValue().amountCents()).isEqualTo(PAID_AMOUNT);
        verify(refundOrderRepository).save(any(RefundOrder.class));
        assertThat(view.status()).isEqualTo(RefundOrderStatus.PENDING);
    }

    /* ================= critical path ================= */

    /**
     * critical-1 超时重扫幂等短路：子单已关闭（上次超时已处理）→ 直接
     * 返回 null 成功——不再关单/建单/受理（handler 幂等，B9.4② 重扫
     * 常态路径）。
     */
    @Test
    @DisplayName("超时重扫已关闭子单：幂等短路，无任何动作")
    void refundByShipTimeout_alreadyClosed_shortCircuit() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.CLOSED));

        final RefundOrderView view = useCase.refundByShipTimeout(SUB_A);

        assertThat(view).isNull();
        verify(orderFacade, never()).closeByTimeout(anyLong());
        verify(refundOrderRepository, never()).findBySubOrderId(anyLong());
        verify(gateway, never()).refund(any(RefundRequest.class));
        verify(refundOrderRepository, never()).save(any());
    }

    /**
     * critical-2 已有退款单防重：一子单一退款单（含 FAILED 可重试态）
     * ——再申请 refund_duplicate 409，无建单/推进/受理（领域模型「no
     * duplicate applications while refunding」；重试走既有单）。
     */
    @Test
    @DisplayName("已有退款单再申请：refund_duplicate 409，无任何动作")
    void applyRefundByBuyer_existingRefund_duplicateRejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(refundOrderRepository.findBySubOrderId(SUB_A))
                .thenReturn(existingRefund(RefundOrderStatus.FAILED, null));

        assertThatThrownBy(() -> useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "重试看看"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_DUPLICATE.code());
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                });
        verify(orderFacade, never()).beginRefund(anyLong());
        verify(gateway, never()).refund(any(RefundRequest.class));
        verify(refundOrderRepository, never()).save(any());
    }

    /**
     * critical-3 失败重试（FAILED 单）：归属校验 → 同一 refundNo 重新受理
     * （渠道幂等键）→ 受理成功落位（FAILED → 退款中归位 + 新流水覆盖）
     * → 落库 → 视图返回 PENDING。
     */
    @Test
    @DisplayName("失败重试：同 refundNo 重新受理 + FAILED 归位 PENDING")
    void retryRefundByBuyer_failedRefund_retryAccepted() {
        when(refundOrderRepository.getByID(7001L))
                .thenReturn(existingRefund(RefundOrderStatus.FAILED, TXN_REFUND_1));
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(gateway.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResult(true, PAY_NO, REFUND_NO, TXN_REFUND_2));

        final RefundOrderView view = useCase.retryRefundByBuyer(BUYER_ID, 7001L);

        verify(gateway).refund(new RefundRequest(PAY_NO, REFUND_NO, PAID_AMOUNT));
        verify(refundOrderRepository).save(any(RefundOrder.class));
        assertThat(view.refundNo()).isEqualTo(REFUND_NO);
        assertThat(view.status()).isEqualTo(RefundOrderStatus.PENDING);
    }

    /* ================= fail path ================= */

    /**
     * fail-1 主单不存在 / 归属买家不符：按不存在呈现（order.master_not_found
     * 404，防越权与存在性泄露——取消/确认收货用例同先例）。
     */
    @Test
    @DisplayName("归属不符：他人子单按不存在呈现 404")
    void applyRefundByBuyer_buyerMismatch_rejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID))
                .thenReturn(new MasterOrder(MASTER_ID, "ORD202609070001", 999L,
                        address(), amount(45000L, 800L, 45800L),
                        List.of(SUB_A), List.of(amount(45000L, 800L, 45800L)),
                        MasterOrderStatus.PAID));

        assertThatThrownBy(() -> useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "退款"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(orderFacade, never()).beginRefund(anyLong());
        verify(gateway, never()).refund(any(RefundRequest.class));
        verify(refundOrderRepository, never()).save(any());
    }

    /**
     * fail-2 子单不存在：404 按不存在呈现（无任何编排动作）。
     */
    @Test
    @DisplayName("子单不存在：404 按不存在呈现")
    void applyRefundByBuyer_subNotFound_rejected() {
        when(subOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> useCase.applyRefundByBuyer(BUYER_ID, 999L, "退款"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(refundOrderRepository, never()).save(any());
    }

    /**
     * fail-3 支付单缺失：退款主目标是资金退还，缺支付单（数据异常）防御
     * 拒绝不静默（与取消编排「缺支付单跳过关单」的区别：取消主目标为
     * 订单+库存，退款主目标即资金侧）。
     */
    @Test
    @DisplayName("支付单缺失：数据异常防御拒绝（payment.not_found）")
    void applyRefundByBuyer_paymentMissing_rejected() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(refundOrderRepository.findBySubOrderId(SUB_A)).thenReturn(null);
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(null);

        assertThatThrownBy(() -> useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "退款"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code()));
        verify(gateway, never()).refund(any(RefundRequest.class));
        verify(refundOrderRepository, never()).save(any());
    }

    /**
     * fail-4 渠道受理拒绝（accepted=false，无后续回调）：退款单 FAILED
     * 落库——订单侧停留退款中（可重试，领域模型「failure keeps order in
     * refunding (retryable)」），申请仍成功返回视图（FAILED 态）。
     */
    @Test
    @DisplayName("渠道受理拒绝：退款单 FAILED 落库，视图返回 FAILED")
    void applyRefundByBuyer_acceptanceRejected_failedPersisted() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(refundOrderRepository.findBySubOrderId(SUB_A)).thenReturn(null);
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf());
        when(gateway.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResult(false, PAY_NO, REFUND_NO, null));

        final RefundOrderView view = useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "退款");

        verify(refundOrderRepository).save(any(RefundOrder.class));
        assertThat(view.status()).isEqualTo(RefundOrderStatus.FAILED);
    }

    /**
     * fail-5 子单状态不可退（待支付/已取消等）：beginRefund 聚合守卫
     * order.sub_status_illegal 透传——同事务整体回滚（无退款单残留：
     * 建单与推进同一事务，异常即回滚）。
     */
    @Test
    @DisplayName("待支付子单申请退款：聚合守卫异常透传（B8.4① 内建）")
    void applyRefundByBuyer_pendingPaymentSubOrder_guardRejects() {
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PENDING_PAYMENT));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());
        when(refundOrderRepository.findBySubOrderId(SUB_A)).thenReturn(null);
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf());
        org.mockito.Mockito.doThrow(
                new BusinessException(
                        EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                        "仅已支付/已发货/已完成的子单可申请退款"))
                .when(orderFacade).beginRefund(SUB_A);

        assertThatThrownBy(() -> useCase.applyRefundByBuyer(BUYER_ID, SUB_A, "退款"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));
        verify(gateway, never()).refund(any(RefundRequest.class));
        verify(refundOrderRepository, never()).save(any());
    }

    /**
     * fail-6 重试非 FAILED 单（退款中/已退款）：refund_status_illegal 拒绝
     * ——重试唯一入口仅对 FAILED 态开放（PENDING 单重复受理防重）。
     */
    @Test
    @DisplayName("重试退款中/已退款单：refund_status_illegal 拒绝")
    void retryRefundByBuyer_notFailed_rejected() {
        when(refundOrderRepository.getByID(7001L))
                .thenReturn(existingRefund(RefundOrderStatus.PENDING, TXN_REFUND_1));
        // 归属校验链（防越权先于状态判定——与取消编排同构）：子单/主单桩就位
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.PAID));
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(master());

        assertThatThrownBy(() -> useCase.retryRefundByBuyer(BUYER_ID, 7001L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));
        verify(gateway, never()).refund(any(RefundRequest.class));
    }

    /**
     * fail-7 重试单不存在：refund_not_found 404（防存在性泄露）。
     */
    @Test
    @DisplayName("重试单不存在：refund_not_found 404")
    void retryRefundByBuyer_notFound_rejected() {
        when(refundOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> useCase.retryRefundByBuyer(BUYER_ID, 999L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
    }
}