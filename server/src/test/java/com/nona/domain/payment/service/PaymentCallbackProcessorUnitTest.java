package com.nona.domain.payment.service;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付回调处理场景测试（TD-11 三层防线的编排消费面，红阶段）。
 * <p>
 * 覆盖：happy——成功回调 → 留痕 + markPaid（订单 onPaid / 库存确认
 * 扣除编排挂点）；失败回调 → 留痕 + markFailed（订单停留待支付）；
 * critical——同号重复回调按已处理应答（B8.2 重复回调只生效一次，不重
 * 放编排）、异号冲突 409 透传；fail——支付单不存在 404（孤儿回调不产
 * 生处理路径）、REFUND 回调拒绝（退款流 退款流 接续）。红阶段失败原因
 * = 实现缺失（处理器方法体 UOE），绿阶段实现后按本矩阵转绿。
 */
@ExtendWith(MockitoExtension.class)
class PaymentCallbackProcessorUnitTest {

    @Mock
    private PaymentOrderRepository repository;

    @Mock
    private OrderFacade orderFacade;

    @Mock
    private InventoryFacade inventoryFacade;

    /**
     * 被测回调处理器（直接装配，红阶段不注册 Spring；mock 注入后于实例
     * 构造，处理器经 setUp 装配）。
     */
    private PaymentCallbackProcessor processor;

    /**
     * 每用例前重建被测处理器（依赖均为 mock，无状态跨用例残留）。
     */
    @BeforeEach
    void setUp() {
        processor = new PaymentCallbackProcessor(repository, orderFacade, inventoryFacade);
    }

    /**
     * 待支付支付单基线。
     */
    private final PaymentOrder pending = new PaymentOrder(
            1L, "PAY202609070001", 100L, 10000L, "MOCK",
            Instant.parse("2026-09-07T10:30:00Z"));

    /**
     * 成功回调基线（渠道校验通过后的标准化事件，流水 TXN-ALIPAY-001）。
     */
    private final ValidatedCallback successCallback = new ValidatedCallback(
            CallbackType.PAY, "PAY202609070001", null, GatewayResult.SUCCESS,
            "TXN-ALIPAY-001", 10000L);

    @Test
    @DisplayName("happy-1 支付成功回调：留痕先行 + markPaid 迁移，编排挂点（子单推进 + 库存确认扣除）")
    void handlePayCallback_success_appliesAndOrchestrates() {
        when(repository.findByPayNo("PAY202609070001")).thenReturn(pending);
        processor.handlePayCallback(successCallback);
        assertThat(pending.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(pending.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-001");
        assertThat(pending.getCallbacks()).hasSize(1);
        verify(orderFacade).onPaid(100L);
        verify(inventoryFacade).confirmDeduct(org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.anyList());
        verify(repository).save(pending);
    }

    @Test
    @DisplayName("happy-2 支付失败回调：留痕 + markFailed 迁移（订单停留待支付等待超时关单）")
    void handlePayCallback_fail_marksFailed() {
        when(repository.findByPayNo("PAY202609070001")).thenReturn(pending);
        processor.handlePayCallback(new ValidatedCallback(
                CallbackType.PAY, "PAY202609070001", null, GatewayResult.FAIL,
                "TXN-ALIPAY-002", 10000L));
        assertThat(pending.getStatus()).isEqualTo(PaymentOrderStatus.FAILED);
        assertThat(pending.getChannelTxnNo()).isEqualTo("TXN-ALIPAY-002");
        assertThat(pending.getCallbacks()).hasSize(1);
        verify(orderFacade, never()).onPaid(100L);
        verify(inventoryFacade, never()).confirmDeduct(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("critical-1 同号重复回调：幂等命中按已处理应答——不重放订单/库存编排（B8.2 只生效一次）")
    void handlePayCallback_duplicateSameTxn_noRepeatOrchestration() {
        when(repository.findByPayNo("PAY202609070001")).thenReturn(pending);
        processor.handlePayCallback(successCallback);
        assertThatThrownBy(() -> processor.handlePayCallback(successCallback))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_STATUS_ILLEGAL.code());
        verify(orderFacade, org.mockito.Mockito.times(1)).onPaid(100L);
        verify(inventoryFacade, org.mockito.Mockito.times(1))
                .confirmDeduct(org.mockito.ArgumentMatchers.eq(100L),
                        org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("critical-2 异号冲突：流水号占用且异号 → callback_duplicate 透传（渠道事故，409）")
    void handlePayCallback_channelTxnConflict_duplicateTransparent() {
        when(repository.findByPayNo("PAY202609070001")).thenReturn(pending);
        processor.handlePayCallback(successCallback);
        assertThatThrownBy(() -> processor.handlePayCallback(new ValidatedCallback(
                CallbackType.PAY, "PAY202609070001", null, GatewayResult.SUCCESS,
                "TXN-WECHAT-999", 10000L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_CALLBACK_DUPLICATE.code());
    }

    @Test
    @DisplayName("critical-3 金额不符：回调金额 ≠ 支付单金额 → amount_mismatch 透传（不入账不编排）")
    void handlePayCallback_amountMismatch_transparent() {
        when(repository.findByPayNo("PAY202609070001")).thenReturn(pending);
        assertThatThrownBy(() -> processor.handlePayCallback(new ValidatedCallback(
                CallbackType.PAY, "PAY202609070001", null, GatewayResult.SUCCESS,
                "TXN-ALIPAY-001", 9999L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_AMOUNT_MISMATCH.code());
        verify(orderFacade, never()).onPaid(100L);
    }

    @Test
    @DisplayName("fail-1 支付单不存在：孤儿回调 → payment.not_found（404，不产生处理路径与孤儿留痕）")
    void handlePayCallback_payNoNotFound_notFound() {
        when(repository.findByPayNo("PAY202609070999")).thenReturn(null);
        assertThatThrownBy(() -> processor.handlePayCallback(new ValidatedCallback(
                CallbackType.PAY, "PAY202609070999", null, GatewayResult.SUCCESS,
                "TXN-ALIPAY-999", 10000L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code());
        verify(orderFacade, never()).onPaid(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("fail-2 REFUND 回调拒绝：退款流归属 退款流 契约，支付回调端口不消费")
    void handlePayCallback_refundType_rejected() {
        assertThatThrownBy(() -> processor.handlePayCallback(new ValidatedCallback(
                CallbackType.REFUND, "PAY202609070001", "RE202609070001",
                GatewayResult.SUCCESS, "TXN-REFUND-001", 10000L)))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).findByPayNo(org.mockito.ArgumentMatchers.anyString());
    }
}