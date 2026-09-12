package com.nona.application.mall;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.payment.ports.AcquireRequest;
import com.nona.domain.payment.ports.AcquireResult;
import com.nona.domain.payment.ports.PaymentAcquireView;
import com.nona.domain.payment.ports.PaymentGateway;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.timeout.TimeoutType;

import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 发起支付用例（承载：POST /mall/payments {orderId}）——买家对
 * 已下单主单发起支付：主单校验（支付发起前必须校验
 * 主单处于可支付状态）→ 支付单创建/复用 → 渠道受理。
 * <p>
 * 编排语义（主单校验→创建/复用→渠道受理，共享编排）：
 * <ol>
 *     <li><b>主单装载与归属</b>：按 orderId 装载主单；不存在或归属买家
 *         不符 → 按不存在呈现（{@code order.master_not_found} 404，防
 *         越权与存在性泄露）；</li>
 *     <li><b>主单状态防线</b>：主单整体状态必须为
 *         {@code PENDING_PAYMENT}（可支付）——已支付/已取消/已关闭等
 *         非可支付状态再发起 → {@code payment.order_invalid}（400）；
 *         主单状态为派生效（子单投影派生），校验由主单装配路径读取；</li>
 *     <li><b>支付单创建/复用</b>：经 {@link PaymentPort#createPendingPayment}
 *         ——同主单已存在待支付单 → 复用（一次支付请求一张支付单，order_id
 *         唯一约束兜底）；不存在 → 补建（金额 = 主单实付快照，超时时长 =
 *         订单域规则值 TimeoutType.ORDER_PAY）；已存在非待支付单 → 拒绝
 *         （{@code payment.order_invalid}，防重复发起）；</li>
 *     <li><b>渠道受理</b>：以支付单号与金额构造 {@link AcquireRequest} 调
 *         {@link PaymentGateway#acquire}（异步回调模型：受理成功 ≠ 支付
 *         成功，结果经回调异步到达）→ 返回受理结果（渠道流水 + 收银台
 *         标识）。</li>
 * </ol>
 * 实现纪律：两入口差异仅在返回载体（AcquireResult 与
 * PaymentAcquireView 融合视图），共享编排提取为 {@link #acquirePending}
 * 私有方法（主单装载归属 → 状态防线 → 支付单创建/复用），避免逻辑复制；
 * 既有 initiatePayment 签名与行为零改动。
 * <p>
 * 装配说明：本类注册为 {@code @Service}（依赖的支付端口实现与回调链路
 * 已接线，容器装配复验见上下文 AcTest）。
 *
 * @author nona9961
 */
@Service
public class PaymentUseCase {

    /**
     * 支付端口（创建/复用待支付支付单）
     */
    private final PaymentPort paymentPort;

    /**
     * 支付渠道（受理锚点）
     */
    private final PaymentGateway gateway;

    /**
     * 主订单仓储（主单装载与可支付状态校验）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 构造发起支付用例。
     *
     * @param paymentPort          支付端口（必填）
     * @param gateway              支付渠道（必填）
     * @param masterOrderRepository 主订单仓储（必填）
     */
    public PaymentUseCase(PaymentPort paymentPort, PaymentGateway gateway,
                          MasterOrderRepository masterOrderRepository) {
        this.paymentPort = paymentPort;
        this.gateway = gateway;
        this.masterOrderRepository = masterOrderRepository;
    }

    /**
     * 买家对主单发起支付（编排语义见类 javadoc）。
     *
     * @param buyerId 买家账号 ID（归属校验，必填）
     * @param orderId 主订单 ID（必填）
     * @return 渠道受理结果（渠道流水 + 收银台参数；支付结果经回调异步到达）
     */
    @CrossTenant
    public AcquireResult initiatePayment(Long buyerId, Long orderId) {
        final PendingPayment pending = acquirePending(buyerId, orderId);
        return gateway.acquire(new AcquireRequest(pending.payNo(), pending.amount()));
    }

    /**
     * 共享编排（两发起入口共用）：主单装载归属 → 可支付状态防线 →
     * 支付单创建/复用。
     *
     * @param buyerId 买家账号 ID（归属校验，必填）
     * @param orderId 主订单 ID（必填）
     * @return 待支付支付单视图（创建或复用的支付单引用 + 金额/超时）
     */
    private PendingPayment acquirePending(Long buyerId, Long orderId) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(orderId);
        if (masterOrder == null || !Objects.equals(buyerId, masterOrder.getBuyerId())) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在或不属于当前买家");
        }
        if (masterOrder.getStatus() != MasterOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_ORDER_INVALID.code(),
                    "主订单处于非可支付状态，不可发起支付");
        }
        final long paidAmount = masterOrder.getAmount().getPaidAmount();
        return paymentPort.createPendingPayment(orderId, paidAmount,
                TimeoutType.ORDER_PAY.durationMillis());
    }

    /**
     * 买家对主单发起支付并返回受理视图（web 端点承载面，
     * 契约补充——POST /mall/payments 返回 InitiatePaymentResult
     * 的用例侧源头）。
     * <p>
     * 编排语义与 {@link #initiatePayment(Long, Long)} 完全一致（主单
     * 装载归属 → 可支付状态防线 → 支付单创建/复用 → 渠道受理）；
     * 差异 = 返回载体为融合视图 {@link PaymentAcquireView}（PendingPayment
     * + AcquireResult，8 字段——payTimeoutMillis 规则回显仅供校验/装配
     * 断言，不对外投影）。
     * <p>
     * 实现纪律：提取私有共享编排方法避免与
     * {@link #initiatePayment(Long, Long)} 逻辑复制；既有
     * initiatePayment 签名与行为零改动（既有测试零波动）。
     *
     * @param buyerId 买家账号 ID（归属校验，必填）
     * @param orderId 主订单 ID（必填）
     * @return 受理视图（8 字段：支付单引用/金额/超时 + 受理结果）
     */
    @CrossTenant
    public PaymentAcquireView initiatePaymentWithView(Long buyerId, Long orderId) {
        final PendingPayment pending = acquirePending(buyerId, orderId);
        final AcquireResult result = gateway.acquire(
                new AcquireRequest(pending.payNo(), pending.amount()));
        return new PaymentAcquireView(pending.paymentOrderId(), pending.payNo(),
                pending.amount(), pending.payTimeoutMillis(), pending.timeoutAt(),
                result.accepted(), result.channelTxnNo(), result.cashierToken());
    }
}