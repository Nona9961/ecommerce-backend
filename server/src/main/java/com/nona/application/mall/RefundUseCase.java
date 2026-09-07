package com.nona.application.mall;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.entity.RefundOrderStatus;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 退款编排用例（买家申请退款 B8.4 + 发货超时系统退款 B9.4② + 失败重试，
 * 跨上下文同事务：建单 → 子单退款中推进 → 渠道受理；资金侧状态由
 * RefundOrder 承载，受理与回调分离的异步模型——受理成功 ≠ 退款成功）。
 * <p>
 * <b>编排序</b>（design 4.4 钉死：一期免平台人工、系统即时受理——
 * C11-2 商家封禁不阻塞退款；发货超时复用同一建单+受理编排）：
 * <ol>
 *     <li><b>买家申请入口（applyRefundByBuyer）</b>：按 subOrderId 装载
 *         子单——不存在 → 按不存在呈现（{@code order.sub_not_found} 404）；
 *         经子单归属主单反查主单并校验买家归属——主单不存在（数据异常
 *         防御）或归属买家不符 → 按不存在呈现（{@code order.master_not_found}
 *         404，防越权与存在性泄露，CancelOrderUseCase/ConfirmReceiptUseCase
 *         同先例）；超时入口（refundByShipTimeout）为系统调度触发（无
 *         买家身份），子单不存在同样 404（数据异常防御，不静默）；</li>
 *     <li><b>幂等短路（超时入口）</b>：目标子单状态非 {@code PAID} →
 *         直接返回成功——发货超时重扫（handler 幂等，B9.4②）的常态
 *         路径（已关闭/退款中/其他终态均不重复建单），与
 *         {@code CancelOrderUseCase.cancelByTimeout} 幂等短路对齐；
 *         短路以<b>子单</b>为判定单元（发货超时操作单元即子单）；</li>
 *     <li><b>防重（一子单一退款单）</b>：{@link RefundOrderRepository#findBySubOrderId}
 *         已有退款单（含 FAILED 可重试态）→ {@code payment.refund_duplicate}
 *         （409，重复申请拒绝——领域模型「no duplicate applications while
 *         refunding」；FAILED 重试走 {@link #retryRefundByBuyer} 同一单，
 *         不新建）；并发窗口由 sub_order_id 唯一约束兜底；</li>
 *     <li><b>提权写段</b>：跨租户写（子单 tenant=shopId 状态推进）与
 *         退款单创建/受理（global + 渠道无租户）在
 *         {@link TenantPrivilege#elevatedInTransaction} 内整体执行——
 *         任一失败整体回滚（方法级 {@link Transactional} 统辖），买家
 *         视角推进店铺数据必须提权（TD-12，取消/支付回调编排同构）；</li>
 *     <li><b>订单侧推进</b>：{@link OrderFacade#beginRefund}（子单
 *         {@code markRefunding}——聚合守卫仅已支付/已发货/已完成可进入
 *         退款中，B8.4① 内建；未支付/终态非法迁移拒绝
 *         {@code order.sub_status_illegal}）+ 主单派生（任一退款中 →
 *         主单退款中）；发货超时入口先 {@link OrderFacade#closeByTimeout}
 *         （已支付 → 已关闭 + 主单派生——履约侧终态定格，资金侧由退款
 *         单承载，领域模型明示 ship-timeout 子单终态 CLOSED 而非
 *         REFUNDED）；</li>
 *     <li><b>建单 + 渠道受理</b>：经 {@link RefundOrderFactory#create}
 *         创建退款单（refundNo = TD-13 REF 规则；金额 = 子单实付，
 *         B8.5③ 退款金额=实付金额；shippedAtApply 快照 = 申请时子单
 *         已发货与否——C9 回补判定锚点，超时入口固定 false 货未出）→
 *         以（payNo + refundNo + 金额）构造 {@link RefundRequest} 调
 *         {@link PaymentGateway#refund}（异步回调模型：受理成功 ≠ 退款
 *         成功，结果经 REFUND 回调异步到达）——受理成功 →
 *         {@code recordAcceptance}（渠道退款流水落位，状态保持退款中）；
 *         受理拒绝（accepted=false，无后续回调）→ {@code markRefundFailed}
 *         （FAILED 可重试，订单侧停留退款中——领域模型「failure keeps
 *         order in refunding (retryable)」）；</li>
 *     <li><b>落库</b>：迁移 + 受理落位经仓储保存（与编排同一方法事务；
 *         受理异常由方法事务整体回滚——不出现「退款单已建但子单未推进」
 *         的半程态）。</li>
 * </ol>
 * <b>失败重试（retryRefundByBuyer）</b>：FAILED 退款单的唯一重试入口——
 * 装载退款单（不存在 → {@code payment.refund_not_found} 404）→ 归属
 * 校验（退款单 → 子单 → 主单 → 买家匹配，不符按不存在呈现）→ 状态
 * 守卫（仅 FAILED 可重试，PENDING/SUCCEEDED →
 * {@code payment.refund_status_illegal}）→ 提权段内以<b>同一 refundNo</b>
 * 重新受理（渠道幂等键「同一退款单只受理一次」，重试复用单号）——
 * 受理成功 → {@code recordAcceptance}（FAILED → 退款中归位 + 新流水
 * 覆盖）；受理拒绝 → 保持 FAILED（无动作）。PENDING 单重试（含受理中
 * 单）由状态守卫拒绝（重复受理防御）。
 * <p>
 * <b>超时复用面（冻结）</b>：发货超时 handler（消费编排 WU）以
 * {@link #refundByShipTimeout} 为唯一入口复用本编排（含建单 + 受理），
 * 不感知内部细节；买家入口保留归属校验，超时入口无身份校验。已支付
 * 取消的引导衔接（B8.6②）：取消编排对已支付子单拒绝（聚合守卫
 * {@code order.sub_status_illegal}）——买家退款引导经本用例
 * {@link #applyRefundByBuyer} 承接，两编排互不耦合。
 * <p>
 * 事务边界 = 用例方法（方法级 {@link Transactional}）；领域方法不做
 * 事务。租户纪律：写放行（elevatedInTransaction）只出现在本类
 * （application 层），domain 内不放行；读放行本用例不需要（主单为
 * global 表，子单读在提权事务段内）。
 * <p>
 * 装配声明：用例类<b>不注册为容器 bean</b>——订单侧端口实现与
 * MasterOrder/SubOrder/RefundOrder/PaymentOrder 仓储实现未接线（红
 * 阶段装配学习，同 CancelOrderUseCase/PaymentCallbackUseCase）；以
 * 构造器注入声明装配契约，Spring 注册（{@code @Service}）随接线 WU
 * 落位恢复；单测以构造器直接装配。
 *
 * @author nona9961
 */
public class RefundUseCase {

    /**
     * 子订单仓储（操作单元装载：申请/超时入口短路判定 + 重试归属链）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 主订单仓储（申请/重试入口归属校验：子单反查主单 + 买家匹配）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 退款单仓储（防重前置判定 + 重试装载 + 落库）
     */
    private final RefundOrderRepository refundOrderRepository;

    /**
     * 支付单仓储（按主单取支付单号 — 原交易定位锚点）
     */
    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * 订单门面（退款/关单推进 + 主单派生）
     */
    private final OrderFacade orderFacade;

    /**
     * 支付渠道（退款受理锚点：payNo + refundNo + 金额）
     */
    private final PaymentGateway gateway;

    /**
     * 退款单工厂（ID/refundNo 生成收敛一处）
     */
    private final RefundOrderFactory refundOrderFactory;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造退款编排用例。
     *
     * @param subOrderRepository    子订单仓储
     * @param masterOrderRepository 主订单仓储
     * @param refundOrderRepository 退款单仓储
     * @param paymentOrderRepository 支付单仓储
     * @param orderFacade           订单门面
     * @param gateway               支付渠道
     * @param refundOrderFactory    退款单工厂
     * @param tenantPrivilege       提权工具
     * @param transactionTemplate   事务模板
     */
    public RefundUseCase(SubOrderRepository subOrderRepository,
                         MasterOrderRepository masterOrderRepository,
                         RefundOrderRepository refundOrderRepository,
                         PaymentOrderRepository paymentOrderRepository,
                         OrderFacade orderFacade,
                         PaymentGateway gateway,
                         RefundOrderFactory refundOrderFactory,
                         TenantPrivilege tenantPrivilege,
                         TransactionTemplate transactionTemplate) {
        this.subOrderRepository = subOrderRepository;
        this.masterOrderRepository = masterOrderRepository;
        this.refundOrderRepository = refundOrderRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.orderFacade = orderFacade;
        this.gateway = gateway;
        this.refundOrderFactory = refundOrderFactory;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 买家申请退款（B8.4：仅已支付/已发货/已完成可申请——聚合守卫内建；
     * 申请即系统即时受理，一期免平台人工 C11-2）。
     *
     * @param buyerId      当前买家账号 ID（归属校验，必填）
     * @param subOrderId   子订单 ID（退款操作单元，必填）
     * @param reason       退款原因（可空——买家不填原因时传 null）
     * @return 退款申请结果视图（refundNo/金额/状态/支付单号；状态
     *         PENDING = 已受理等待渠道回调）
     */
    @Transactional
    public RefundOrderView applyRefundByBuyer(Long buyerId, Long subOrderId, String reason) {
        // ① 子单装载：不存在 → 按不存在呈现（404，无任何编排动作）
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        // ② 归属校验：子单反查主单并匹配买家——主单不存在（数据异常防御）或
        //    归属买家不符 → 按不存在呈现（防越权与存在性泄露，取消/确认收货先例）
        final MasterOrder masterOrder = masterOrderRepository.getByID(subOrder.getMasterOrderId());
        if (masterOrder == null || !masterOrder.getBuyerId().equals(buyerId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在（归属校验按不存在呈现）", 404);
        }
        // ③ 防重（一子单一退款单）：已有退款单（含 FAILED 可重试态）→ 重复申请
        //    拒绝（领域模型「no duplicate applications while refunding」；FAILED
        //    重试走 retryRefundByBuyer 同一单，不新建）；并发窗口由 sub_order_id
        //    唯一约束兜底
        if (refundOrderRepository.findBySubOrderId(subOrderId) != null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_DUPLICATE.code(),
                    "该子订单已有退款单（一子单一退款单：重复申请拒绝，失败重试走既有单）", 409);
        }
        // ④ 支付单装载（原交易定位锚点）：缺支付单（数据异常）防御拒绝不静默——
        //    退款主目标即资金侧（与取消编排「缺支付单跳过关单」的区别）
        final PaymentOrder paymentOrder =
                paymentOrderRepository.findByOrderId(masterOrder.getId());
        if (paymentOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                    "支付单不存在（数据异常防御）", 404);
        }
        // 发货快照必须在 beginRefund 迁移前捕获（申请时刻定点：C9 判定锚点，
        // 防退款流程中子单状态演进污染判定）；子单仅已支付为未发货
        final boolean shippedAtApply = subOrder.getStatus() != SubOrderStatus.PAID;
        // ⑤ 提权写段（TD-12：跨租户写子单 tenant=shopId 必须放行；退款单创建/受理
        //    为 global + 渠道路径）：订单侧推进 → 建单（金额 = 子单实付 B8.5③）→
        //    渠道受理（异步回调模型：受理成功 ≠ 退款成功）
        final RefundOrder refundOrder;
        try {
            refundOrder = tenantPrivilege.elevatedInTransaction(
                    transactionTemplate, () -> {
                        orderFacade.beginRefund(subOrderId);
                        final RefundOrder created = refundOrderFactory.create(
                                paymentOrder.getPayNo(), subOrderId,
                                subOrder.getAmount().getPaidAmount(), shippedAtApply, reason);
                        final RefundResult result = gateway.refund(new RefundRequest(
                                created.getPayNo(), created.getRefundNo(), created.getAmount()));
                        acceptRefund(created, result);
                        return created;
                    });
        } catch (final RuntimeException e) {
            // 聚合守卫/受理异常原样透传 → 方法事务整体回滚（无半程态）
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("退款申请编排提权事务失败", e);
        }
        // ⑥ 落库：迁移 + 受理落位与编排同一方法事务（受理异常由方法事务整体
        //    回滚——不出现「退款单已建但子单未推进」的半程态）
        refundOrderRepository.save(refundOrder);
        return new RefundOrderView(refundOrder.getId(), refundOrder.getRefundNo(),
                refundOrder.getAmount(), refundOrder.getStatus(), refundOrder.getPayNo());
    }

    /**
     * 发货超时系统退款（B9.4②：商家逾期未发货 → 自动关单 + 退款；
     * handler 复用入口，同 {@code CancelOrderUseCase.cancelByTimeout}
     * 模式——系统触发无买家身份，不做归属校验）。
     *
     * @param subOrderId 子订单 ID（已支付未发货；必填）
     * @return 退款单视图（PENDING = 已受理等待渠道回调；短路成功时
     *         返回 null）
     */
    @Transactional
    public RefundOrderView refundByShipTimeout(Long subOrderId) {
        // ① 子单装载：不存在同样 404（数据异常防御，不静默）
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        // ② 幂等短路：目标子单状态非 PAID → 直接返回成功 null——发货超时重扫
        //    （handler 幂等，B9.4②）的常态路径（已关闭/退款中/其他终态均不重复建单）
        if (subOrder.getStatus() != SubOrderStatus.PAID) {
            return null;
        }
        // ③ 防重并发窗口：已有退款单（买家申请流正在受理）→ 短路返回 null 不重复
        //    建单（系统重扫幂等语义；sub_order_id 唯一约束兜底并发双建）
        if (refundOrderRepository.findBySubOrderId(subOrderId) != null) {
            return null;
        }
        // ④ 支付单装载（原交易定位锚点，同申请入口防御）
        final PaymentOrder paymentOrder =
                paymentOrderRepository.findByOrderId(subOrder.getMasterOrderId());
        if (paymentOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                    "支付单不存在（数据异常防御）", 404);
        }
        // ⑤ 提权写段：发货超时入口与买家申请分支互斥——closeByTimeout（已支付 →
        //    已关闭，履约侧终态定格）→ 建单（shippedAtApply 固定 false：超时前提
        //    即货未出）→ 渠道受理
        final RefundOrder refundOrder;
        try {
            refundOrder = tenantPrivilege.elevatedInTransaction(
                    transactionTemplate, () -> {
                        orderFacade.closeByTimeout(subOrderId);
                        final RefundOrder created = refundOrderFactory.create(
                                paymentOrder.getPayNo(), subOrderId,
                                subOrder.getAmount().getPaidAmount(), false, null);
                        final RefundResult result = gateway.refund(new RefundRequest(
                                created.getPayNo(), created.getRefundNo(), created.getAmount()));
                        acceptRefund(created, result);
                        return created;
                    });
        } catch (final RuntimeException e) {
            // 聚合守卫/受理异常原样透传 → 方法事务整体回滚（无半程态）
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("发货超时退款编排提权事务失败", e);
        }
        // ⑥ 落库（同一方法事务）
        refundOrderRepository.save(refundOrder);
        return new RefundOrderView(refundOrder.getId(), refundOrder.getRefundNo(),
                refundOrder.getAmount(), refundOrder.getStatus(), refundOrder.getPayNo());
    }

    /**
     * 退款失败重试（FAILED 退款单唯一重试入口：同一 refundNo 重新受理，
     * 渠道幂等键复用——领域模型「failed is retryable」）。
     *
     * @param buyerId      当前买家账号 ID（归属校验，必填）
     * @param refundOrderId 退款单 ID（必填）
     * @return 重试结果视图（成功 = PENDING 等待渠道回调；重试仍被拒 =
     *         FAILED 保持）
     */
    @Transactional
    public RefundOrderView retryRefundByBuyer(Long buyerId, Long refundOrderId) {
        // ① 装载退款单：不存在 → refund_not_found 404（防存在性泄露）
        final RefundOrder refundOrder = refundOrderRepository.getByID(refundOrderId);
        if (refundOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_NOT_FOUND.code(),
                    "退款单不存在", 404);
        }
        // ② 归属校验（防越权先于状态判定，与取消编排同构）：退款单 → 子单 → 主单
        //    → 买家匹配，不符按不存在呈现
        final SubOrder subOrder = subOrderRepository.getByID(refundOrder.getSubOrderId());
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        final MasterOrder masterOrder = masterOrderRepository.getByID(subOrder.getMasterOrderId());
        if (masterOrder == null || !masterOrder.getBuyerId().equals(buyerId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在（归属校验按不存在呈现）", 404);
        }
        // ③ 状态守卫：仅 FAILED 可重试（PENDING/SUCCEEDED → refund_status_illegal
        //    ——PENDING 单重复受理防御，SUCCEEDED 终态不可逆）
        if (refundOrder.getStatus() != RefundOrderStatus.FAILED) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_REFUND_STATUS_ILLEGAL.code(),
                    "仅失败退款单可重试（退款中/已退款单重复受理防御）");
        }
        // ④ 提权段内以同一 refundNo 重新受理（渠道幂等键「同一退款单只受理一次」，
        //    重试复用单号）：受理成功 → recordAcceptance（FAILED → 退款中归位 +
        //    新流水覆盖）；受理拒绝 → 保持 FAILED（无动作）
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final RefundResult result = gateway.refund(new RefundRequest(
                        refundOrder.getPayNo(), refundOrder.getRefundNo(), refundOrder.getAmount()));
                if (result.accepted()) {
                    refundOrder.recordAcceptance(result.channelRefundTxnNo());
                }
                return null;
            });
        } catch (final RuntimeException e) {
            // 受理异常原样透传 → 方法事务整体回滚
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("退款重试编排提权事务失败", e);
        }
        // ⑤ 落库（同一方法事务）
        refundOrderRepository.save(refundOrder);
        return new RefundOrderView(refundOrder.getId(), refundOrder.getRefundNo(),
                refundOrder.getAmount(), refundOrder.getStatus(), refundOrder.getPayNo());
    }

    /**
     * 渠道受理结果落位（申请/超时/重试共用）：受理成功 → 渠道退款流水落位
     * （状态保持退款中——受理 ≠ 退款结果，异步回调模型）；受理拒绝（无后续
     * 回调）→ 退款失败落位（FAILED 可重试，订单侧停留退款中——领域模型
     * 「failure keeps order in refunding (retryable)」）。
     *
     * @param refundOrder 退款单（受理后迁移落位）
     * @param result      渠道受理结果
     */
    private void acceptRefund(RefundOrder refundOrder, RefundResult result) {
        if (result.accepted()) {
            refundOrder.recordAcceptance(result.channelRefundTxnNo());
        } else {
            refundOrder.markRefundFailed();
        }
    }
}