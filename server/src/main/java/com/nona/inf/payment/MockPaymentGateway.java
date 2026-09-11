package com.nona.inf.payment;

import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayCallbackPayload;
import com.nona.domain.payment.ports.GatewayPaymentStatus;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.ports.RefundRequest;
import com.nona.domain.payment.ports.RefundResult;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mock 支付渠道（PaymentGateway 当前唯一实现）：按真实渠道异步回调形态
 * 仿真三剧本（成功 / 失败 / 不回调），供全流程演示与测试。
 * <p>
 * 剧本路由（行为可控，规则收敛本类）：
 * <ul>
 *     <li><b>按 payNo 标记</b>：payNo 尾段携带 {@code #SUCCESS} /
 *         {@code #FAIL} / {@code #NOCALLBACK} 标记（大小写敏感、需位于单号
 *         末尾）即命中对应剧本——标记得用于演示「指定某单的剧本」；
 *         标记视为渠道侧约定，不透传给业务（业务单号本身不含标记）；</li>
 *     <li><b>默认剧本兜底</b>：无匹配标记的单号走默认剧本（配置
 *         {@code nona.mock-gateway.default-script}，默认 SUCCESS）——
 *         演示「整体切换失败/超时场景」时改配置即可。</li>
 * </ul>
 * 三剧本语义（受理均成功，差异在回调与查询）：
 * <ul>
 *     <li>SUCCESS 剧本：受理成功 →（演示方/测试按剧本构造）SUCCESS 回调
 *         → 渠道侧 query 恒 SUCCESS；</li>
 *     <li>FAIL 剧本：受理成功 → FAIL 回调（支付失败，业务侧订单保持待支付）
 *         → 渠道侧 query 恒 FAIL；</li>
 *     <li>NOCALLBACK 剧本：受理成功后<b>永不回调</b>（触发业务侧超时关单
 *        路径）→ 渠道侧 query 恒 WAIT_PAY。</li>
 * </ul>
 * 模型边界：
 * <ul>
 *     <li><b>无内部状态</b>：受理不落任何渠道记录、不主动外发回调——
 *         回调由调用方（演示脚本/测试）按剧本构造 payload 送达
 *         {@link #handleCallback}；query 按剧本规则恒定性返回（不依赖是否
 *         受理过）；重复受理/回调乱序等业务侧幂等防线归业务侧回调编排；</li>
 *     <li>回调校验：<b>渠道语义自身防御</b>——单号空白/金额非正/类型与
 *         字段不配套一律拒绝（BusinessException）；金额一致性（回调金额
 *         = 受理金额）核对归业务侧聚合；</li>
 *     <li>退款：与支付同构的受理 + REFUND 回调异步模型；退款结果跟随
 *         payNo 剧本（SUCCESS 剧本退款成功回调 / FAIL 剧本失败回调 /
 *         NOCALLBACK 剧本无退款回调）——单一规则、可预测；</li>
 *     <li>渠道流水号（channelTxnNo/channelRefundTxnNo）：受理时经
 *         Snowflake 生成（唯一数字串，业务侧唯一约束防线的素材），回调
 *         时由调用方回传同一流水号。</li>
 * </ul>
 *
 * @author nona9961
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    /**
     * 剧本标记分隔符（payNo 尾段：{@code payNo + 分隔符 + 剧本名}）。
     */
    private static final String SCRIPT_SEPARATOR = "#";

    /**
     * 成功剧本标记。
     */
    private static final String SCRIPT_SUCCESS = "SUCCESS";

    /**
     * 失败剧本标记。
     */
    private static final String SCRIPT_FAIL = "FAIL";

    /**
     * 不回调剧本标记。
     */
    private static final String SCRIPT_NO_CALLBACK = "NOCALLBACK";

    /**
     * 默认剧本名（配置项 {@code nona.mock-gateway.default-script}；
     * 取值 {@code SUCCESS}/{@code FAIL}/{@code NOCALLBACK}，非法值回落
     * SUCCESS）。
     */
    private final String defaultScript;

    /**
     * 构造 mock 渠道。
     *
     * @param defaultScript 默认剧本（无 payNo 标记时生效；可空，回落 SUCCESS）
     */
    public MockPaymentGateway(@Value("${nona.mock-gateway.default-script:SUCCESS}") String defaultScript) {
        this.defaultScript = defaultScript;
    }

    /**
     * {@inheritDoc}
     * <p>
     * mock 语义：payNo/金额合法即受理成功（受理 ≠ 支付结果）；受理生成渠道
     * 流水号与收银台标识；剧本决定后续回调形态与 query 结果。
     */
    @Override
    public AcquireResult acquire(AcquireRequest request) {
        if (request == null || StringUtils.isBlank(request.payNo())
                || request.amountCents() == null || request.amountCents() <= 0) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_INVALID_ARGUMENT.code(),
                    "支付受理参数非法：支付单号与金额（正数）必须提供");
        }
        final String channelTxnNo = nextChannelTxnNo();
        return new AcquireResult(true, request.payNo(), channelTxnNo, "mock-cashier:" + channelTxnNo);
    }

    /**
     * {@inheritDoc}
     * <p>
     * mock 语义：渠道自身参数防御（单号空白/金额非正/类型与字段不配套
     * 拒绝），校验通过返回标准化回调事件；不校验幂等与状态一致性（归
     * 业务侧编排）。
     */
    @Override
    public ValidatedCallback handleCallback(GatewayCallbackPayload payload) {
        if (payload == null || payload.type() == null || StringUtils.isBlank(payload.payNo())
                || StringUtils.isBlank(payload.channelTxnNo()) || payload.result() == null
                || payload.amountCents() == null || payload.amountCents() <= 0
                || (payload.type() == CallbackType.REFUND && StringUtils.isBlank(payload.refundNo()))
                || (payload.type() == CallbackType.PAY && StringUtils.isNotBlank(payload.refundNo()))) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "渠道回调非法：单号/流水号/结果/金额与类型字段配套必须完整");
        }
        return new ValidatedCallback(payload.type(), payload.payNo(), payload.refundNo(),
                payload.result(), payload.channelTxnNo(), payload.amountCents());
    }

    /**
     * {@inheritDoc}
     * <p>
     * mock 语义：payNo/refundNo/金额合法即受理成功；受理生成渠道退款流水
     * 号；退款结果经 REFUND 回调异步到达（跟随 payNo 剧本）。
     */
    @Override
    public RefundResult refund(RefundRequest request) {
        if (request == null || StringUtils.isBlank(request.payNo())
                || StringUtils.isBlank(request.refundNo())
                || request.amountCents() == null || request.amountCents() <= 0) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_INVALID_ARGUMENT.code(),
                    "退款受理参数非法：支付单号/退款单号与金额（正数）必须提供");
        }
        return new RefundResult(true, request.payNo(), request.refundNo(), nextChannelTxnNo());
    }

    /**
     * {@inheritDoc}
     * <p>
     * mock 语义：按 payNo 剧本恒定性返回渠道侧状态（SUCCESS→SUCCESS；
     * FAIL→FAIL；NOCALLBACK→WAIT_PAY），不依赖是否受理过。
     */
    @Override
    public GatewayPaymentStatus query(String payNo) {
        if (StringUtils.isBlank(payNo)) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_GATEWAY_INVALID_ARGUMENT.code(),
                    "支付状态查询参数非法：支付单号必须提供");
        }
        return switch (resolveScript(payNo)) {
            case SCRIPT_SUCCESS -> GatewayPaymentStatus.SUCCESS;
            case SCRIPT_FAIL -> GatewayPaymentStatus.FAIL;
            default -> GatewayPaymentStatus.WAIT_PAY;
        };
    }

    /**
     * 解析 payNo 命中的剧本（尾段标记优先，无标记走默认剧本）。
     *
     * @param payNo 业务支付单号（已校验非空白）
     * @return 剧本名（SUCCESS/FAIL/NOCALLBACK；未知标记回落默认剧本）
     */
    private String resolveScript(String payNo) {
        if (payNo.endsWith(SCRIPT_SEPARATOR + SCRIPT_SUCCESS)) {
            return SCRIPT_SUCCESS;
        }
        if (payNo.endsWith(SCRIPT_SEPARATOR + SCRIPT_FAIL)) {
            return SCRIPT_FAIL;
        }
        if (payNo.endsWith(SCRIPT_SEPARATOR + SCRIPT_NO_CALLBACK)) {
            return SCRIPT_NO_CALLBACK;
        }
        return effectiveDefaultScript();
    }

    /**
     * 解析默认剧本配置（非法值回落 SUCCESS）。
     *
     * @return 生效的默认剧本名
     */
    private String effectiveDefaultScript() {
        if (SCRIPT_SUCCESS.equals(defaultScript) || SCRIPT_FAIL.equals(defaultScript)
                || SCRIPT_NO_CALLBACK.equals(defaultScript)) {
            return defaultScript;
        }
        return SCRIPT_SUCCESS;
    }

    /**
     * 生成渠道流水号（Snowflake 数字串）。
     *
     * @return 渠道流水号
     */
    private String nextChannelTxnNo() {
        return String.valueOf(IDUtils.generateID());
    }
}