package com.nona.application.support;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.RefundCallbackPort;
import com.nona.domain.payment.ports.ValidatedCallback;
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
 * 退款回调编排用例场景测试（退款回调接线阶段契约：payment → order →
 * inventory 同事务编排——B8.5 退款成功订单置已退款 + I7 未发货回补，
 * 红阶段）。
 * <p>
 * 覆盖：happy——成功回调全链（留痕先行 → SUCCEEDED → 提权段内
 * completeRefund + 未发货回补（明细从订单项快照装配））；已发货退款
 * 不回补（C9）；失败回调（仅退款单迁移 FAILED，订单/库存不动——可重
 * 试）。critical——同号重复回调幂等（B8.5 只生效一次，留痕仍落库不
 * 重放）、异号流水冲突 409、金额不符 400、发货超时路径（子单 CLOSED
 * 幂等跳过订单侧 + 回补照常）。fail——孤儿回调 404、PAY 类型拒绝、
 * null 拒绝、编排异常整体回滚语义。
 * <p>
 * 依赖装配：仓库/门面/提权/事务全部 mock 承载（编排契约断言面）；提权
 * 事务以 mock 直执行（真实事务回滚属应用层注解面，冒烟清单覆盖）。
 * 红阶段失败原因 = 实现缺失（handleRefundCallback 方法体 UOE）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class RefundCallbackUseCaseUnitTest {

    /**
     * 测试子单 / 店铺 / SKU / 主单
     */
    private static final long MASTER_ID = 100L;
    private static final long SUB_A = 101L;
    private static final long SHOP_A = 4001L;
    private static final long SKU_A1 = 3001L;
    private static final long SKU_A2 = 3002L;

    /**
     * 支付单号 / 退款单号 / 金额（分，= 子单实付）
     */
    private static final String PAY_NO = "PAY20260907000001";
    private static final String REFUND_NO = "REF202609070001";
    private static final long AMOUNT = 35800L;

    /**
     * 受理流水（受理落位）+ 回调流水（成功/失败同号——异号走冲突 409）
     */
    private static final String TXN_REFUND = "TXN-REFUND-001";

    @Mock
    private RefundOrderRepository repository;

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
     * 被测退款回调编排用例（红阶段不注册 Spring；依赖全 mock，setUp
     * 装配）。
     */
    private RefundCallbackPort useCase;

    /**
     * 退款单基线（用例内演进：迁移/留痕状态原地推进——桩与断言共享实例）。
     */
    private RefundOrder refund;

    /**
     * 每用例前重建被测用例与退款单基线（依赖 mock，无状态跨用例残留）；
     * 提权事务桩统一 lenient 化（critical/fail 用例不触达提权段）。
     */
    @BeforeEach
    void setUp() {
        useCase = new RefundCallbackUseCase(repository, subOrderRepository,
                orderFacade, inventoryFacade, tenantPrivilege, transactionTemplate);
        refund = refundOrderNotShipped();
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
     * 未发货退款单基线（PENDING + 已受理落位流水 TXN_REFUND；快照
     * shippedAtApply=false）。
     */
    private static RefundOrder refundOrderNotShipped() {
        return new RefundOrder(7001L, REFUND_NO, PAY_NO, SUB_A, AMOUNT,
                false, "不想要了", RefundOrderStatus.PENDING, TXN_REFUND, List.of());
    }

    /**
     * 已发货退款单基线（shippedAtApply=true——退款成功不回补，C9）。
     */
    private static RefundOrder refundOrderShipped() {
        return new RefundOrder(7001L, REFUND_NO, PAY_NO, SUB_A, AMOUNT,
                true, "货不对板", RefundOrderStatus.PENDING, TXN_REFUND, List.of());
    }

    /**
     * 子单（SKU_A1 × 2 + SKU_A2 × 3，goods 35000 freight 800 paid 35800）；
     * 状态按退款路径注入：REFUNDING（主动退款）/ CLOSED（发货超时）。
     */
    private static SubOrder subA(SubOrderStatus status) {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(35000L, 800L, 35800L),
                List.of(item(SKU_A1, 10000L, 2), item(SKU_A2, 5000L, 3)), status, null);
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
     * 退款成功回调基线（渠道校验通过后的标准化事件，type=REFUND）。
     */
    private static ValidatedCallback successCallback() {
        return new ValidatedCallback(CallbackType.REFUND, PAY_NO, REFUND_NO,
                GatewayResult.SUCCESS, TXN_REFUND, AMOUNT);
    }

    /**
     * 退款失败回调基线。
     */
    private static ValidatedCallback failCallback() {
        return new ValidatedCallback(CallbackType.REFUND, PAY_NO, REFUND_NO,
                GatewayResult.FAIL, TXN_REFUND, AMOUNT);
    }

    /* ================= happy path ================= */

    /**
     * happy-1 成功回调全链（未发货，主动退款路径）：留痕先行 → SUCCEEDED
     * → 提权段内 completeRefund（子单退款中 → 已退款 + 主单派生）→
     * 未发货回补 restore（明细 = 订单项快照 SKU+数量，与预占/扣减同源
     * 对称，I7 幂等键兜底）→ 退款单落库。
     */
    @Test
    @DisplayName("成功回调（未发货）：留痕 + SUCCEEDED + completeRefund + 回补 restore")
    void handleRefundCallback_success_notShipped_restoresInventory() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.REFUNDING));

        useCase.handleRefundCallback(successCallback());

        // 聚合侧：SUCCEEDED + 流水落位 + 回调原文 1 条留痕
        assertThat(refund.getStatus()).isEqualTo(RefundOrderStatus.SUCCEEDED);
        assertThat(refund.getChannelRefundTxnNo()).isEqualTo(TXN_REFUND);
        assertThat(refund.getCallbacks()).hasSize(1);
        // 编排面：订单门面退款成功推进 + 未发货回补（明细 = 订单项快照装配）
        verify(orderFacade).completeRefund(SUB_A);
        verify(inventoryFacade).restore(eq(SUB_A), eq(List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3))));
        verify(repository).save(any(RefundOrder.class));
    }

    /**
     * happy-2 已发货退款成功：不回补库存（C9——已发货/已完成货已出，退货
     * 物流 II 期深化留接口位）；订单侧退款成功推进照常。
     */
    @Test
    @DisplayName("成功回调（已发货）：不回补库存，订单侧推进照常")
    void handleRefundCallback_success_shipped_noRestore() {
        refund = refundOrderShipped();
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.REFUNDING));

        useCase.handleRefundCallback(successCallback());

        verify(orderFacade).completeRefund(SUB_A);
        verify(inventoryFacade, never()).restore(anyLong(), anyList());
        verify(repository).save(any(RefundOrder.class));
    }

    /**
     * happy-3 失败回调：仅退款单 FAILED（可重试）——订单侧停留退款中
     * （不推进/不回补，领域模型「failure keeps order in refunding
     * (retryable)」）。
     */
    @Test
    @DisplayName("失败回调：FAILED 落库，订单/库存编排不发生")
    void handleRefundCallback_fail_marksFailedOnly() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);

        useCase.handleRefundCallback(failCallback());

        assertThat(refund.getStatus()).isEqualTo(RefundOrderStatus.FAILED);
        assertThat(refund.getCallbacks()).hasSize(1);
        verify(orderFacade, never()).completeRefund(anyLong());
        verify(inventoryFacade, never()).restore(anyLong(), anyList());
        verify(repository).save(any(RefundOrder.class));
    }

    /* ================= critical path ================= */

    /**
     * critical-1 同号重复回调幂等（B8.5 只生效一次）：重复成功回调命中
     * 状态守卫 refund_status_illegal 透传——留痕第二条仍落库（对账不依
     * 赖迁移成败），订单/库存编排不重放（completeRefund/restore 恰一次）。
     */
    @Test
    @DisplayName("同号重复回调：refund_status_illegal 透传 + 留痕落库 + 编排不重放")
    void handleRefundCallback_duplicateSameTxn_noRepeatOrchestration() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.REFUNDING));

        useCase.handleRefundCallback(successCallback());

        assertThatThrownBy(() -> useCase.handleRefundCallback(successCallback()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code()));

        verify(orderFacade, times(1)).completeRefund(SUB_A);
        verify(inventoryFacade, times(1)).restore(anyLong(), anyList());
        verify(repository, times(2)).save(any(RefundOrder.class));
        assertThat(refund.getCallbacks()).hasSize(2);
    }

    /**
     * critical-2 异号流水冲突（渠道事故优先诊断）：回调流水 ≠ 受理流水
     * → callback_duplicate(409) 透传；本次回调仍留痕落库；编排不触发。
     */
    @Test
    @DisplayName("异号流水冲突：callback_duplicate(409) + 留痕落库 + 不编排")
    void handleRefundCallback_txnConflict_duplicateTransparent() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);

        assertThatThrownBy(() -> useCase.handleRefundCallback(new ValidatedCallback(
                CallbackType.REFUND, PAY_NO, REFUND_NO, GatewayResult.SUCCESS,
                "TXN-REFUND-OTHER", AMOUNT)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code());
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                });
        verify(repository).save(any(RefundOrder.class));
        verify(orderFacade, never()).completeRefund(anyLong());
        verify(inventoryFacade, never()).restore(anyLong(), anyList());
    }

    /**
     * critical-3 金额不符：回调金额 ≠ 退款单金额 → amount_mismatch 拒绝
     * （半额/超额不入账）；状态停留退款中；本次回调仍留痕落库。
     */
    @Test
    @DisplayName("金额不符：amount_mismatch + 状态停留退款中 + 留痕落库")
    void handleRefundCallback_amountMismatch_transparent() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);

        assertThatThrownBy(() -> useCase.handleRefundCallback(new ValidatedCallback(
                CallbackType.REFUND, PAY_NO, REFUND_NO, GatewayResult.SUCCESS,
                TXN_REFUND, AMOUNT - 1L)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code()));

        assertThat(refund.getStatus()).isEqualTo(RefundOrderStatus.PENDING);
        assertThat(refund.getCallbacks()).hasSize(1);
        verify(repository).save(any(RefundOrder.class));
        verify(orderFacade, never()).completeRefund(anyLong());
    }

    /**
     * critical-4 发货超时路径成功回调：子单已关闭（履约侧终态定格，领域
     * 模型明示 CLOSED 而非 REFUNDED）——completeRefund 幂等跳过订单侧，
     * 未发货回补照常（货未出，I7）；资金侧退款单 SUCCEEDED。
     */
    @Test
    @DisplayName("发货超时路径：CLOSED 子单订单侧跳过 + 未发货回补照常")
    void handleRefundCallback_success_closedSubOrder_restoresInventory() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);
        when(subOrderRepository.getByID(SUB_A)).thenReturn(subA(SubOrderStatus.CLOSED));

        useCase.handleRefundCallback(successCallback());

        // 订单侧推进经 completeRefund 收敛（CLOSED 幂等跳过内建于门面）
        verify(orderFacade).completeRefund(SUB_A);
        // 未发货回补照常（发货超时前提即货未出）
        verify(inventoryFacade).restore(eq(SUB_A), eq(List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3))));
        verify(repository).save(any(RefundOrder.class));
    }

    /* ================= fail path ================= */

    /**
     * fail-1 退款单不存在：孤儿回调（回调先于退款单到达）→ refund_not_found
     * 404——不产生处理路径与孤儿留痕（无装载即无留痕/落库）。
     */
    @Test
    @DisplayName("孤儿回调：refund_not_found 404，无留痕无编排")
    void handleRefundCallback_refundNotFound_notFound() {
        when(repository.findByRefundNo("REF202609079999")).thenReturn(null);

        assertThatThrownBy(() -> useCase.handleRefundCallback(new ValidatedCallback(
                CallbackType.REFUND, PAY_NO, "REF202609079999", GatewayResult.SUCCESS,
                "TXN-REFUND-999", AMOUNT)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode())
                            .isEqualTo(EcommerceBusinessCode.PAYMENT_REFUND_NOT_FOUND.code());
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
        verify(repository, never()).save(any());
        verify(orderFacade, never()).completeRefund(anyLong());
    }

    /**
     * fail-2 PAY 回调拒绝：支付回调归支付编排，退款端口不消费——装载都
     * 未发生（对称隔离）。
     */
    @Test
    @DisplayName("PAY 回调拒绝：gateway_callback_invalid，不进入装载")
    void handleRefundCallback_payType_rejected() {
        assertThatThrownBy(() -> useCase.handleRefundCallback(new ValidatedCallback(
                CallbackType.PAY, PAY_NO, null, GatewayResult.SUCCESS,
                "TXN-PAY-001", AMOUNT)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code()));
        verify(repository, never()).findByRefundNo(anyString());
    }

    /**
     * fail-3 null 回调拒绝：入口守卫最先（渠道事故防御），无任何动作。
     */
    @Test
    @DisplayName("null 回调拒绝：入口守卫，无任何动作")
    void handleRefundCallback_null_rejected() {
        assertThatThrownBy(() -> useCase.handleRefundCallback(null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code()));
        verify(repository, never()).findByRefundNo(anyString());
    }

    /**
     * fail-4 编排异常整体回滚语义：迁移成功后订单侧推进异常 → 异常原样
     * 透传（TD-07 三域原子）——库存回补不发生、退款单不落库（方法事务
     * 回滚，退款单不出现「已退款但订单未推进」的半程态）。
     */
    @Test
    @DisplayName("编排异常：订单推进失败 → 异常透传，回补不开始、退款单不落库")
    void handleRefundCallback_orchestrationFailure_rollsBack() {
        when(repository.findByRefundNo(REFUND_NO)).thenReturn(refund);
        doThrow(new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                "子单非法迁移（模拟订单侧推进失败）"))
                .when(orderFacade).completeRefund(SUB_A);

        assertThatThrownBy(() -> useCase.handleRefundCallback(successCallback()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getBusinessCode())
                                .isEqualTo(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code()));

        verify(inventoryFacade, never()).restore(anyLong(), anyList());
        verify(repository, never()).save(any());
    }
}