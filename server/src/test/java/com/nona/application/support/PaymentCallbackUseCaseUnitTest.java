package com.nona.application.support;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.domain.payment.repo.PaymentOrderRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付回调编排用例场景测试（支付回调接线阶段契约：payment → order →
 * inventory 同事务编排）。
 * <p>
 * 覆盖：happy——成功回调全链路（留痕先行 → markPaid → 提权段内 onPaid
 * + 逐子单扣减明细从订单项快照装配）；失败回调（仅支付单迁移，订单/库
 * 存不动）。critical——同号重复回调幂等（只生效一次，留痕仍落库
 * 不重放编排）、异号冲突 409、金额不符 400、多子单编排序。fail——
 * 支付单不存在 404（孤儿回调）、REFUND 回调拒绝、null 回调拒绝、编排
 * 异常整体回滚语义（订单推进异常 → 库存扣减不发生、支付单不落库）。
 * <p>
 * 依赖装配：仓库/门面/提权/事务全部 mock 承载（编排契约断言面）；提权
 * 事务以 mock 直执行（真实事务回滚属应用层注解面，冒烟清单覆盖）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class PaymentCallbackUseCaseUnitTest {

    /**
     * 测试主订单 / 子单 / 店铺 / SKU（与取消编排测试基线同构）
     */
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long SUB_B = 102L;
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;
    private static final long SKU_A1 = 3001L;
    private static final long SKU_A2 = 3002L;
    private static final long SKU_B1 = 3101L;

    /**
     * 支付单号 / 实付金额（分，= 主单实付）
     */
    private static final String PAY_NO = "PAY20260907000001";
    private static final long PAID_AMOUNT = 45800L;

    /**
     * 成功回调流水（渠道受理流水，唯一约束防线素材）
     */
    private static final String TXN_SUCCESS = "TXN-ALIPAY-001";
    private static final String TXN_FAIL = "TXN-ALIPAY-002";

    @Mock
    private PaymentOrderRepository repository;

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private OrderFacade orderFacade;

    @Mock
    private InventoryFacade inventoryFacade;

    @Mock
    private TenantPrivilege tenantPrivilege;

    @Mock
    private TransactionTemplate transactionTemplate;

    /**
     * 被测回调编排用例（依赖全 mock，setUp 装配）。
     */
    private PaymentCallbackUseCase useCase;

    /**
     * 待支付支付单（用例内演进：迁移/留痕状态原地推进）。
     */
    private PaymentOrder pending;

    /**
     * 每用例前重建被测用例与支付单基线（依赖 mock，无状态跨用例残留）；
     * 提权事务桩统一 lenient 化——critical/fail 用例不触达提权段，
     * lenient 豁免 UnnecessaryStubbing（取消编排用例同款）。
     */
    @BeforeEach
    void setUp() {
        useCase = new PaymentCallbackUseCase(repository, subOrderRepository,
                orderFacade, inventoryFacade, tenantPrivilege, transactionTemplate);
        try {
            lenient().when(tenantPrivilege.elevatedInTransaction(
                            eq(transactionTemplate), any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
            // 落库律独立事务（被拒回调留痕 save 走 REQUIRES_NEW）：stub execute
            // 直接执行回调（同 TenantPrivilegeUnitTest mock 形态）
            lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                final org.springframework.transaction.support.TransactionCallback<Object> callback =
                        invocation.getArgument(0);
                return callback.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
            });
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
        pending = pendingPayment();
    }

    /* ================= fixtures ================= */

    /**
     * 待支付支付单基线（关联主单，金额 = 主单实付，创建时刻 = 当前时间
     * ——非断言面，不用绝对日期魔法值）。
     */
    private static PaymentOrder pendingPayment() {
        return new PaymentOrder(9001L, PAY_NO, MASTER_ID, PAID_AMOUNT, "MOCK", Instant.now());
    }

    /**
     * 支付成功回调基线（渠道校验通过后的标准化事件）。
     */
    private static ValidatedCallback successCallback(String txnNo) {
        return new ValidatedCallback(CallbackType.PAY, PAY_NO, null,
                GatewayResult.SUCCESS, txnNo, PAID_AMOUNT);
    }

    /**
     * 支付失败回调基线。
     */
    private static ValidatedCallback failCallback(String txnNo) {
        return new ValidatedCallback(CallbackType.PAY, PAY_NO, null,
                GatewayResult.FAIL, txnNo, PAID_AMOUNT);
    }

    /**
     * 子单 A（SKU_A1 × 2 + SKU_A2 × 3，goods 35000 freight 800）。
     */
    private static SubOrder subA() {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(35000L, 800L, 35800L),
                List.of(item(SKU_A1, 10000L, 2), item(SKU_A2, 5000L, 3)));
    }

    /**
     * 子单 B（SKU_B1 × 1，goods 10000 freight 0）。
     */
    private static SubOrder subB() {
        return new SubOrder(SUB_B, MASTER_ID, SHOP_B, "SO202609070002", address(),
                amount(10000L, 0L, 10000L), List.of(item(SKU_B1, 10000L, 1)));
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

    /* ================= happy path ================= */

    /**
     * happy-1 成功回调全链路：留痕先行 → markPaid → 提权段内 onPaid(主单)
     * + 逐子单 confirmDeduct（明细 = 订单项快照 SKU+数量，与预占同源
     * 对称）→ 支付单落库；聚合侧状态/流水/留痕演进断言。
     */
    @Test
    @DisplayName("成功回调：留痕+markPaid+提权段内 onPaid 与逐子单确认扣减（明细=订单项快照）")
    void handlePayCallback_success_orchestratesOrderAndInventory() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        useCase.handlePayCallback(successCallback(TXN_SUCCESS));

        // 聚合侧：PAID + 渠道流水落位 + 回调原文 1 条留痕
        assertThat(pending.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(pending.getChannelTxnNo()).isEqualTo(TXN_SUCCESS);
        assertThat(pending.getCallbacks()).hasSize(1);
        // 编排面：订单门面主单推进 + 逐子单扣减明细按订单项快照装配
        verify(orderFacade).onPaid(MASTER_ID);
        verify(inventoryFacade).confirmDeduct(eq(SUB_A),
                eq(List.of(new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3))));
        verify(inventoryFacade).confirmDeduct(eq(SUB_B),
                eq(List.of(new StockChangeItem(SKU_B1, 1))));
        verify(repository).save(pending);
    }

    /**
     * happy-2 失败回调：留痕 + markFailed（订单停留待支付等待超时关单）
     * ——不推进订单/库存，支付单落库。
     */
    @Test
    @DisplayName("失败回调：留痕+markFailed，订单/库存编排不发生")
    void handlePayCallback_fail_marksFailedOnly() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);

        useCase.handlePayCallback(failCallback(TXN_FAIL));

        assertThat(pending.getStatus()).isEqualTo(PaymentOrderStatus.FAILED);
        assertThat(pending.getChannelTxnNo()).isEqualTo(TXN_FAIL);
        assertThat(pending.getCallbacks()).hasSize(1);
        verify(orderFacade, never()).onPaid(anyLong());
        verify(inventoryFacade, never()).confirmDeduct(anyLong(), anyList());
        verify(repository).save(pending);
    }

    /**
     * happy-3 单子单主单：扣减恰一次、明细 = 唯一子单订单项快照。
     */
    @Test
    @DisplayName("单子单成功回调：确认扣减一次，明细=子单订单项")
    void handlePayCallback_singleSubOrder_deductOnce() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subB()));

        useCase.handlePayCallback(successCallback(TXN_SUCCESS));

        verify(inventoryFacade, times(1)).confirmDeduct(anyLong(), anyList());
        verify(inventoryFacade).confirmDeduct(eq(SUB_B),
                eq(List.of(new StockChangeItem(SKU_B1, 1))));
    }

    /* ================= critical path ================= */

    /**
     * critical-1 同号重复回调幂等（只生效一次）：首次成功编排重放
     * 一次；重复回调命中状态守卫（status_illegal）——留痕第二条仍落库
     * （对账不依赖迁移成败），订单/库存编排不重放。
     */
    @Test
    @DisplayName("同号重复回调：status_illegal 透传 + 留痕落库 + 编排不重放（幂等命中）")
    void handlePayCallback_duplicateSameTxn_noRepeatOrchestration() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        useCase.handlePayCallback(successCallback(TXN_SUCCESS));

        assertThatThrownBy(() -> useCase.handlePayCallback(successCallback(TXN_SUCCESS)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code()));

        // 幂等：订单/库存编排只发生一次（不重放）；留痕两条均落库（save 共 2 次）
        verify(orderFacade, times(1)).onPaid(MASTER_ID);
        verify(inventoryFacade, times(2)).confirmDeduct(anyLong(), anyList());
        verify(repository, times(2)).save(pending);
        assertThat(pending.getCallbacks()).hasSize(2);
    }

    /**
     * critical-2 异号冲突（渠道事故优先诊断）：流水号已占用且异号 →
     * callback_duplicate(409) 透传；本次回调仍留痕落库；编排不重放。
     */
    @Test
    @DisplayName("异号冲突：callback_duplicate(409) 透传 + 留痕落库 + 编排不重放")
    void handlePayCallback_channelTxnConflict_duplicateTransparent() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        useCase.handlePayCallback(successCallback(TXN_SUCCESS));

        assertThatThrownBy(() -> useCase.handlePayCallback(
                successCallback("TXN-WECHAT-999")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code()));

        verify(orderFacade, times(1)).onPaid(MASTER_ID);
        verify(repository, times(2)).save(pending);
    }

    /**
     * critical-3 金额不符：回调金额 ≠ 支付单金额 → amount_mismatch 拒绝
     * （不入账不编排）；状态停留待支付；本次回调仍留痕落库。
     */
    @Test
    @DisplayName("金额不符：amount_mismatch 拒绝 + 状态停留待支付 + 留痕落库")
    void handlePayCallback_amountMismatch_transparent() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);

        assertThatThrownBy(() -> useCase.handlePayCallback(new ValidatedCallback(
                CallbackType.PAY, PAY_NO, null, GatewayResult.SUCCESS,
                TXN_SUCCESS, PAID_AMOUNT - 1L)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code()));

        assertThat(pending.getStatus()).isEqualTo(PaymentOrderStatus.PENDING_PAYMENT);
        assertThat(pending.getCallbacks()).hasSize(1);
        verify(repository).save(pending);
        verify(orderFacade, never()).onPaid(anyLong());
    }

    /**
     * critical-4 多子单编排序：onPaid(主单) → 逐子单 confirmDeduct ——
     * 订单侧全部推进先于库存扣减开始（同事务编排序钉死）。
     */
    @Test
    @DisplayName("编排序：onPaid 先于逐子单确认扣减（同事务编排序）")
    void handlePayCallback_orchestrationOrder() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));

        useCase.handlePayCallback(successCallback(TXN_SUCCESS));

        final InOrder inOrder = inOrder(orderFacade, inventoryFacade, repository);
        inOrder.verify(orderFacade).onPaid(MASTER_ID);
        inOrder.verify(inventoryFacade).confirmDeduct(eq(SUB_A), anyList());
        inOrder.verify(inventoryFacade).confirmDeduct(eq(SUB_B), anyList());
        inOrder.verify(repository).save(pending);
    }

    /* ================= fail path ================= */

    /**
     * fail-1 支付单不存在：孤儿回调（回调先于支付单到达）→ not_found
     * 404——不产生处理路径与孤儿留痕（无装载即无留痕/落库）。
     */
    @Test
    @DisplayName("支付单不存在：孤儿回调 not_found(404)，无留痕无编排")
    void handlePayCallback_payNoNotFound_notFound() {
        when(repository.findByPayNo("PAY20260907000999")).thenReturn(null);

        assertThatThrownBy(() -> useCase.handlePayCallback(new ValidatedCallback(
                CallbackType.PAY, "PAY20260907000999", null, GatewayResult.SUCCESS,
                "TXN-ALIPAY-999", PAID_AMOUNT)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(repository, never()).save(any());
        verify(orderFacade, never()).onPaid(anyLong());
    }

    /**
     * fail-2 REFUND 回调拒绝：退款回调归退款编排接续，支付回调端口不消费
     * ——装载都未发生。
     */
    @Test
    @DisplayName("REFUND 回调拒绝：gateway_callback_invalid，不进入装载")
    void handlePayCallback_refundType_rejected() {
        assertThatThrownBy(() -> useCase.handlePayCallback(new ValidatedCallback(
                CallbackType.REFUND, PAY_NO, "RE202609070001",
                GatewayResult.SUCCESS, "TXN-REFUND-001", PAID_AMOUNT)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode()).isEqualTo(
                                EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code()));
        verify(repository, never()).findByPayNo(anyString());
    }

    /**
     * fail-3 null 回调拒绝：入口守卫最先（渠道事故防御），无任何动作。
     */
    @Test
    @DisplayName("null 回调拒绝：入口守卫，无任何动作")
    void handlePayCallback_null_rejected() {
        assertThatThrownBy(() -> useCase.handlePayCallback(null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode()).isEqualTo(
                                EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code()));
        verify(repository, never()).findByPayNo(anyString());
    }

    /**
     * fail-4 编排异常整体回滚语义：迁移成功后订单门面推进异常 → 异常原样
     * 透传（三域原子）——库存扣减不发生、支付单不落库（方法事务
     * 回滚，支付单不出现「已支付但订单未推进」的半程态）。
     */
    @Test
    @DisplayName("编排异常：订单推进失败 → 异常透传，扣减不开始、支付单不落库")
    void handlePayCallback_orchestrationFailure_rollsBack() {
        when(repository.findByPayNo(PAY_NO)).thenReturn(pending);
        doThrow(new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                "子单非法迁移（模拟订单侧推进失败）"))
                .when(orderFacade).onPaid(MASTER_ID);

        assertThatThrownBy(() -> useCase.handlePayCallback(successCallback(TXN_SUCCESS)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));

        verify(inventoryFacade, never()).confirmDeduct(anyLong(), anyList());
        verify(repository, never()).save(any());
    }
}