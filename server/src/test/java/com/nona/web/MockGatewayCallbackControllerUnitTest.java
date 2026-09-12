package com.nona.web;

import com.nona.api.HttpResponse;
import com.nona.api.internal.CallbackType;
import com.nona.api.internal.GatewayResult;
import com.nona.api.internal.MockGatewayCallbackRequest;
import com.nona.application.support.PaymentCallbackUseCase;
import com.nona.application.support.RefundCallbackUseCase;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.payment.MockPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * mock 网关回调送达控制器场景测试（回调 HTTP 送达面接线契约，红阶段）。
 * <p>
 * 覆盖：happy——合法 PAY 回调分发到支付回调编排（回调事件字段逐项透
 * 传不改写）、合法 REFUND 回调分发到退款回调编排；critical——REFUND
 * 缺退款单号 / PAY 携退款单号（类型与字段不配套）渠道校验拒绝透传、
 * 编排业务异常原样透传（controller 不 try/catch）；fail——单号空白 /
 * 缺渠道流水 / 金额非正 / 类型缺失渠道校验拒绝且两编排零消费。
 * <p>
 * 依赖装配：两个回调编排用例 mock 承载（分发断言面），渠道校验用真
 * {@link MockPaymentGateway}（无状态纯函数校验，语义收敛渠道侧——避免
 * mock 桩重实现渠道校验造成双源）；被测控制器每用例前重建（项目先例
 * 装配纪律：禁止字段初始化携带 mock 的 new）。JSR 结构校验（@Valid/@NonNull
 * 触发面）是容器装配面，归装配面清单（AcTest），非本单元面。
 * <p>
 * 时间断言：本单元无时间语义面（回调事件无时间字段），不适用相对窗口
 * 约束；留痕时刻断言归装配面 AcTest（相对窗口）。
 * 红阶段失败原因 = 实现缺失（handleCallback 方法体 UOE）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class MockGatewayCallbackControllerUnitTest {

    /**
     * 支付回调编排用例（mock——分发断言面）
     */
    @Mock
    private PaymentCallbackUseCase paymentCallbackUseCase;

    /**
     * 退款回调编排用例（mock——分发断言面）
     */
    @Mock
    private RefundCallbackUseCase refundCallbackUseCase;

    /**
     * 被测回调送达控制器（每用例前重建：渠道真件 + 编排 mock 装配）
     */
    private MockGatewayCallbackController controller;

    /**
     * 每用例前重建被测控制器（项目先例：@BeforeEach 重建，禁止字段初始
     * 化 new X(mock)——mock 由 MockitoExtension 逐方法重建，真渠道无状态
     * 可安全重建）。
     */
    @BeforeEach
    void setUp() {
        controller = new MockGatewayCallbackController(
                new MockPaymentGateway("SUCCESS"), paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * happy：合法 PAY 回调（成功/失败剧本同构，成功径见下）→ 分发到支付
     * 回调编排：回调事件字段（type/payNo/refundNo/result/channelTxnNo/
     * amountCents）逐项透传不经控制器改写；退款编排零消费；成功响应
     * 形状（固定成功码）。
     */
    @Test
    @DisplayName("合法PAY回调分发到支付回调编排且字段透传")
    void payCallback_validPayload_dispatchedToPayOrchestration() {
        final HttpResponse<Void> response = controller.handleCallback(payRequest("PAY20260910001",
                null, GatewayResult.SUCCESS, "TXN-CB-DELIVERY-1", 5300L));
        assertThat(response.code()).isEqualTo(HttpResponse.SUCCESS);
        assertThat(response.success()).isTrue();
        verify(paymentCallbackUseCase).handlePayCallback(argThat(cb ->
                cb.type() == com.nona.domain.payment.ports.CallbackType.PAY
                        && "PAY20260910001".equals(cb.payNo())
                        && cb.refundNo() == null
                        && cb.result() == com.nona.domain.payment.ports.GatewayResult.SUCCESS
                        && "TXN-CB-DELIVERY-1".equals(cb.channelTxnNo())
                        && cb.amountCents() == 5300L));
        verifyNoInteractions(refundCallbackUseCase);
    }

    /**
     * happy：合法 REFUND 回调（refundNo 必填配套）→ 分发到退款回调编排，
     * 退款单号随回调事件透传；支付编排零消费。
     */
    @Test
    @DisplayName("合法REFUND回调分发到退款回调编排且退款单号透传")
    void refundCallback_validPayload_dispatchedToRefundOrchestration() {
        final HttpResponse<Void> response = controller.handleCallback(refundRequest());
        assertThat(response.code()).isEqualTo(HttpResponse.SUCCESS);
        verify(refundCallbackUseCase).handleRefundCallback(argThat(cb ->
                cb.type() == com.nona.domain.payment.ports.CallbackType.REFUND
                        && "PAY20260910001".equals(cb.payNo())
                        && "RE20260910002".equals(cb.refundNo())
                        && cb.result() == com.nona.domain.payment.ports.GatewayResult.SUCCESS
                        && "TXN-RF-DELIVERY-2".equals(cb.channelTxnNo())
                        && cb.amountCents() == 5300L));
        verifyNoInteractions(paymentCallbackUseCase);
    }

    /**
     * critical：REFUND 类型缺退款单号（类型与字段不配套）→ 渠道校验拒绝
     * （{@code payment.gateway_callback_invalid}，400）异常透传，两编排零
     * 消费——控制器未绕开渠道校验自行分发。
     */
    @Test
    @DisplayName("REFUND缺退款单号渠道校验拒绝且不消费")
    void refundTypeWithoutRefundNo_rejectedTransparently() {
        assertThatThrownBy(() -> controller.handleCallback(refundRequestWithoutNo()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getBusinessCode())
                            .isEqualTo("payment.gateway_callback_invalid");
                    assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400);
                });
        verifyNoInteractions(paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * critical：PAY 类型携带退款单号（类型与字段不配套）→ 渠道校验拒绝
     * 透传，两编排零消费。
     */
    @Test
    @DisplayName("PAY携带退款单号渠道校验拒绝且不消费")
    void payTypeWithRefundNo_rejectedTransparently() {
        assertThatThrownBy(() -> controller.handleCallback(payRequest("PAY20260910001",
                "RE20260910002", GatewayResult.SUCCESS, "TXN-CB-DELIVERY-3", 5300L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_callback_invalid"));
        verifyNoInteractions(paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * critical：编排业务异常（支付单不存在 404）原样透传——controller 为
     * 薄壳不 try/catch（AuthController 先例同构），应答映射归全局异常
     * 处理；退款编排不触达。桩 lenient 化：红阶段占位 UOE 抢先抛出，
     * stub 未消费不触发 UnnecessaryStubbing（项目先例同款豁免）。
     */
    @Test
    @DisplayName("编排业务异常原样透传不吞不映射")
    void orchestrationBusinessException_passedThroughUntouched() {
        final BusinessException notFound = new BusinessException(
                EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                "支付单不存在（孤儿回调不产生处理路径）", 404);
        lenient().doThrow(notFound).when(paymentCallbackUseCase).handlePayCallback(any());
        assertThatThrownBy(() -> controller.handleCallback(payRequest("PAY20260910001",
                null, GatewayResult.SUCCESS, "TXN-CB-DELIVERY-4", 5300L)))
                .isSameAs(notFound);
        verify(refundCallbackUseCase, never()).handleRefundCallback(any());
    }

    /**
     * fail：支付单号空白 → 渠道校验拒绝（{@code payment.gateway_callback_invalid}）
     * 透传不消费（空白单号渠道防御，与缺失同语义）。
     */
    @Test
    @DisplayName("支付单号空白渠道校验拒绝且不消费")
    void blankPayNo_rejectedNotConsumed() {
        assertThatThrownBy(() -> controller.handleCallback(payRequest("  ",
                null, GatewayResult.SUCCESS, "TXN-CB-DELIVERY-5", 5300L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_callback_invalid"));
        verifyNoInteractions(paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * fail：缺渠道流水号 → 渠道校验拒绝透传不消费（流水号是业务侧唯一
     * 约束防线的素材，渠道强校验）。
     */
    @Test
    @DisplayName("缺渠道流水号渠道校验拒绝且不消费")
    void missingChannelTxnNo_rejectedNotConsumed() {
        assertThatThrownBy(() -> controller.handleCallback(payRequest("PAY20260910001",
                null, GatewayResult.SUCCESS, null, 5300L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_callback_invalid"));
        verifyNoInteractions(paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * fail：回调金额非正（0 与负数）→ 渠道校验拒绝透传不消费（金额一致
     * 性核对归业务侧聚合，正数形态由渠道防御）。
     */
    @Test
    @DisplayName("回调金额非正渠道校验拒绝且不消费")
    void nonPositiveAmount_rejectedNotConsumed() {
        assertThatThrownBy(() -> controller.handleCallback(payRequest("PAY20260910001",
                null, GatewayResult.SUCCESS, "TXN-CB-DELIVERY-6", 0L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.handleCallback(payRequest("PAY20260910001",
                null, GatewayResult.SUCCESS, "TXN-CB-DELIVERY-7", -100L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_callback_invalid"));
        verifyNoInteractions(paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * fail：回调类型缺失（null）→ 渠道校验拒绝透传不消费（无法判定业务
     * 语义即拒绝；非法枚举字符串属反序列化面，归装配面 AcTest）。
     */
    @Test
    @DisplayName("回调类型缺失渠道校验拒绝且不消费")
    void nullType_rejectedNotConsumed() {
        assertThatThrownBy(() -> controller.handleCallback(new MockGatewayCallbackRequest(
                null, "PAY20260910001", null, GatewayResult.SUCCESS, "TXN-CB-DELIVERY-8", 5300L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("payment.gateway_callback_invalid"));
        verifyNoInteractions(paymentCallbackUseCase, refundCallbackUseCase);
    }

    /**
     * 构造合法 PAY 回调请求（refundNo 按配套语义传入）。
     *
     * @param payNo         支付单号
     * @param refundNo      退款单号（PAY 时传 null）
     * @param result        渠道结果
     * @param channelTxnNo  渠道流水号
     * @param amountCents   回调金额（分）
     * @return 回调请求体
     */
    private static MockGatewayCallbackRequest payRequest(String payNo, String refundNo,
                                                         GatewayResult result, String channelTxnNo,
                                                         Long amountCents) {
        return new MockGatewayCallbackRequest(CallbackType.PAY, payNo, refundNo,
                result, channelTxnNo, amountCents);
    }

    /**
     * 构造合法 REFUND 回调请求（refundNo 必填配套）。
     *
     * @return 回调请求体
     */
    private static MockGatewayCallbackRequest refundRequest() {
        return new MockGatewayCallbackRequest(CallbackType.REFUND, "PAY20260910001",
                "RE20260910002", GatewayResult.SUCCESS, "TXN-RF-DELIVERY-2", 5300L);
    }

    /**
     * 构造缺退款单号的 REFUND 回调请求（类型与字段不配套素材）。
     *
     * @return 回调请求体
     */
    private static MockGatewayCallbackRequest refundRequestWithoutNo() {
        return new MockGatewayCallbackRequest(CallbackType.REFUND, "PAY20260910001",
                null, GatewayResult.SUCCESS, "TXN-RF-DELIVERY-2", 5300L);
    }
}