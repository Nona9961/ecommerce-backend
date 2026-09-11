package com.nona.web;

import com.nona.api.HttpResponse;
import com.nona.api.internal.MockGatewayCallbackApi;
import com.nona.api.internal.MockGatewayCallbackRequest;
import com.nona.application.support.PaymentCallbackUseCase;
import com.nona.application.support.RefundCallbackUseCase;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayCallbackPayload;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.ports.ValidatedCallback;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * mock 网关回调送达控制器（支付/退款 HTTP 送达面）：内部回调端点，无
 * 身份校验语义（网关/系统触发，与买家/商家/平台门户路由隔离），
 * <b>mock/演示专用</b>——接真实渠道时本端点与安全链放行项一并移除。
 * <p>
 * 控制器保持薄壳（AuthController 同构）：JSR 结构校验 + 渠道回调校验
 * （复用 {@link PaymentGateway#handleCallback}，缺字段/金额非正/类型与
 * 字段不配套 → {@code payment.gateway_callback_invalid}）+ 按回调类型
 * 分发（PAY → {@link PaymentCallbackUseCase}、REFUND →
 * {@link RefundCallbackUseCase}），不承载任何业务逻辑；幂等、状态迁
 * 移与留痕归回调编排，业务异常由全局 ExceptionAdviser 统一映射，
 * 本类不做 try/catch。
 * <p>
 * 装配契约：{@link PaymentGateway} 端口构造器注入（当前唯一实现
 * MockPaymentGateway 为 @Component，按端口装配，未来真实渠道仅换实现
 * 类）；两个回调编排用例已 @Service 注册，同构造器注入。
 * <p>
 * 安全链放行（web 层实现面）：{@code /internal/**} 需在
 * SecurityConfig.authorizeHttpRequests 中 permitAll（本端点无身份语义；
 * 回调入口 jwt 过滤器对无 token 请求放行的前提是该路径不进
 * authenticated 拦截）。
 *
 * @author nona9961
 */
@RestController
public class MockGatewayCallbackController implements MockGatewayCallbackApi {

    /**
     * 支付渠道端口（回调校验与标准化：payload → 校验通过的回调事件）
     */
    private final PaymentGateway paymentGateway;

    /**
     * 支付回调编排用例（只消费 PAY 回调）
     */
    private final PaymentCallbackUseCase paymentCallbackUseCase;

    /**
     * 退款回调编排用例（只消费 REFUND 回调）
     */
    private final RefundCallbackUseCase refundCallbackUseCase;

    /**
     * 构造回调送达控制器。
     *
     * @param paymentGateway          支付渠道端口（必填；校验回调载荷）
     * @param paymentCallbackUseCase  支付回调编排用例（必填）
     * @param refundCallbackUseCase   退款回调编排用例（必填）
     */
    public MockGatewayCallbackController(PaymentGateway paymentGateway,
                                         PaymentCallbackUseCase paymentCallbackUseCase,
                                         RefundCallbackUseCase refundCallbackUseCase) {
        this.paymentGateway = paymentGateway;
        this.paymentCallbackUseCase = paymentCallbackUseCase;
        this.refundCallbackUseCase = refundCallbackUseCase;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现语义（接线面，按接口 javadoc 钉死）：{@code @Valid} 结构校验 →
     * 请求体字段（type/payNo/refundNo/result/channelTxnNo/amountCents，
     * api 面枚举按名直转域枚举）构造渠道载荷 → {@code paymentGateway.
     * handleCallback} 渠道校验（拒绝即异常透传）→ 按 type 分发到对应
     * 回调编排。
     */
    @Override
    @PostMapping("/internal/mock-gateway/callback")
    public HttpResponse<Void> handleCallback(@Valid @RequestBody MockGatewayCallbackRequest request) {
        final ValidatedCallback validated = paymentGateway.handleCallback(new GatewayCallbackPayload(
                request.type() == null ? null : CallbackType.valueOf(request.type().name()),
                request.payNo(), request.refundNo(),
                request.result() == null ? null : GatewayResult.valueOf(request.result().name()),
                request.channelTxnNo(), request.amountCents()));
        if (validated.type() == CallbackType.PAY) {
            paymentCallbackUseCase.handlePayCallback(validated);
        } else {
            refundCallbackUseCase.handleRefundCallback(validated);
        }
        return HttpResponse.ok();
    }
}