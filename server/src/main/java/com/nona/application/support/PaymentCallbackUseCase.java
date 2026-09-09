package com.nona.application.support;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentCallbackRecord;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.domain.payment.ports.PaymentCallbackPort;
import com.nona.domain.payment.ports.ValidatedCallback;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import com.nona.util.IDUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Service;

/**
 * 支付回调编排用例（回调编排接线阶段的核心编排：渠道回调 → 支付单
 * 装载/核对 → 留痕 → 迁移 → 同事务推进订单与库存）。
 * <p>
 * 承载位依据：回调入口无身份校验（网关/系统触发），编排横跨
 * payment/order/inventory 三域且含跨租户写（子单/库存 tenant=shopId）
 * ——按设计「跨端共用编排」落 application.support；跨上下文协作全经
 * 各域端口（OrderFacade/InventoryFacade）与仓储契约，应用层承载事务
 * 边界（方法级 {@link Transactional}）与提权写段（TD-12 写放行只允许
 * 出现在 application 层用例方法上）。
 * <p>
 * <b>编排语义</b>（实现接线依据，按 PaymentCallbackPort 接口 javadoc +
 * PaymentOrder 聚合守卫契约 + 取消/完成编排先例逐条钉死）：
 * <ol>
 *     <li><b>入口守卫</b>：回调事件 null → {@code payment.gateway_callback_invalid}
 *         （渠道事故防御）；type 非 PAY（REFUND 回调）→
 *         {@code payment.gateway_callback_invalid}（退款回调归退款编排
 *         接续，本用例不消费）；</li>
 *     <li><b>装载</b>：按 payNo {@code findByPayNo} 装载支付单——不存在 →
 *         {@code payment.not_found}（404，「回调先于支付单到达」的孤儿
 *         回调不产生处理路径与孤儿留痕）；</li>
 *     <li><b>防线三留痕先行</b>：回调原文构造 {@link PaymentCallbackRecord}
 *         （IDUtils 生成留痕记录主键 + 收到时刻 = 当前时间）经
 *         {@link PaymentOrder#appendCallbackRecord} 追加，<b>先留痕后判
 *         迁移</b>——重复/失败/金额不符等被拒回调同样留痕，对账不依赖
 *         迁移成败；</li>
 *     <li><b>防线二状态迁移</b>：result=SUCCESS → {@code markPaid}；
 *         result=FAIL → {@code markFailed}（金额/流水/状态守卫判定顺序
 *         见 PaymentOrder 类 javadoc）——<b>迁移守卫被拒的落库律</b>：
 *         同号重复回调（{@code payment.status_illegal}，B8.2 幂等命中）、
 *         异号冲突（{@code payment.callback_duplicate} 409）、金额不符
 *         （{@code payment.amount_mismatch}）一律先
 *         {@code repository.save}（留痕持久化，对账可查）再原样透传异常
 *         ——幂等语义成立（不重放订单/库存编排），异常到渠道的应答映射
 *         由回调接入挂点承载；</li>
 *     <li><b>成功编排（首次迁移成功后，同事务）</b>：提权写段
 *         （{@link TenantPrivilege#elevatedInTransaction}）内依序——
 *         ① {@link OrderFacade#onPaid}（主单下全部子单 待支付→已支付 +
 *         主单状态派生，M9；子单空集合/主单不存在的防御由订单门面
 *         承载）；② 逐子单 {@link InventoryFacade#confirmDeduct}（I4
 *         确认扣减：明细从子单订单项快照装配（SKU + 数量），与下单
 *         预占/取消回滚的 items 装配同源对称；扣减粒度 = 子单，与
 *         preoccupy/rollback 对称）；任一步失败 → 异常透传 → 方法事务
 *         <b>整体回滚</b>（payment → order → inventory 三域原子，
 *         TD-07 同步编排）；</li>
 *     <li><b>落库</b>：迁移 + 留痕经 {@code repository.save} 持久化
 *         （与成功编排同一方法事务；编排异常时不单独落库——事务回滚
 *         已保证原子，支付单不出现「已支付但订单未推进」的半程态）；</li>
 *     <li><b>失败回调</b>：仅 markFailed + save——订单停留待支付等待
 *         超时关单（B8.3），不推进订单/库存。</li>
 * </ol>
 * <p>
 * 幂等语义汇总（B8.2「重复回调不重复处理」）：幂等锚点 = 支付单状态
 * 迁移守卫（同号重复回调命中 status_illegal 即已处理应答，编排不重放）；
 * 本用例不做订单侧附加短路（订单侧状态由支付单守卫上游唯一驱动）。
 * <p>
 * 事务边界 = 用例方法（方法级 {@link Transactional}）；领域方法不做
 * 事务；提权写段（跨租户写子单/库存）只出现在本类（application 层）。
 * <p>
 * 装配声明：用例类<b>不注册为容器 bean</b>——仓储实现（order/payment）
 * 未接线（装配学习，同取消/完成编排用例）；以构造器注入声明装配契约，
 * Spring 注册（{@code @Service}）随接线阶段落位恢复；单测以构造器
 * 直接装配。
 *
 * @author nona9961
 */
@Service
public class PaymentCallbackUseCase implements PaymentCallbackPort {

    /**
     * 支付单仓储（装载/留痕/迁移落库锚点）
     */
    private final PaymentOrderRepository repository;

    /**
     * 子订单仓储（成功编排：逐子单扣减明细装载，items 与预占同源对称）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 订单门面（成功编排：子单推进 + 主单派生）
     */
    private final OrderFacade orderFacade;

    /**
     * 库存门面（成功编排：逐子单确认扣减 I4）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行：子单/库存
     * tenant=shopId 在回调上下文推进）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造回调编排用例。
     *
     * @param repository          支付单仓储（必填）
     * @param subOrderRepository  子订单仓储（必填）
     * @param orderFacade         订单门面（必填）
     * @param inventoryFacade     库存门面（必填）
     * @param tenantPrivilege     提权工具（必填）
     * @param transactionTemplate 事务模板（必填）
     */
    public PaymentCallbackUseCase(PaymentOrderRepository repository,
                                  SubOrderRepository subOrderRepository,
                                  OrderFacade orderFacade,
                                  InventoryFacade inventoryFacade,
                                  TenantPrivilege tenantPrivilege,
                                  TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.subOrderRepository = subOrderRepository;
        this.orderFacade = orderFacade;
        this.inventoryFacade = inventoryFacade;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 处理支付回调（成功/失败统一入口；7 步编排语义见类 javadoc）。
     * <p>
     * 落库律（决策 5/6）：迁移守卫被拒（重复/异号/金额不符）→ 先
     * {@code repository.save}（留痕持久化，对账可查）再原样透传；编排
     * 异常（迁移成功后 onPaid/confirmDeduct 失败）→ 不单独落库——方法
     * 级 {@link Transactional} 整体回滚，支付单不出现「已支付但订单未
     * 推进」的半程态。
     *
     * @param callback 渠道回调校验通过后的标准化事件（handleCallback
     *                 返回，必填；type 必须为 PAY）
     */
    @Override
    @Transactional
    public void handlePayCallback(ValidatedCallback callback) {
        // 1. 入口守卫：null / 非 PAY（REFUND 归退款编排接续）→ 渠道事故防御，无任何动作
        if (callback == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "回调事件不能为空");
        }
        if (callback.type() != CallbackType.PAY) {
            throw new BusinessException(
                    EcommerceBusinessCode.PAYMENT_GATEWAY_CALLBACK_INVALID.code(),
                    "支付回调处理端口只消费 PAY 回调（REFUND 归退款编排接续）");
        }
        // 2. 装载：孤儿回调（回调先于支付单到达）不产生处理路径与孤儿留痕
        final PaymentOrder order = repository.findByPayNo(callback.payNo());
        if (order == null) {
            throw new BusinessException(EcommerceBusinessCode.PAYMENT_NOT_FOUND.code(),
                    "支付单不存在（孤儿回调不产生处理路径）", 404);
        }
        // 3. 防线三留痕先行：回调原文构造留痕记录追加——先留痕后判迁移，
        //    重复/失败/金额不符等被拒回调同样留痕，对账不依赖迁移成败
        final PaymentCallbackRecord record = new PaymentCallbackRecord(
                IDUtils.generateID(), order.getId(), callback.type(), callback.payNo(),
                callback.refundNo(), callback.result(), callback.channelTxnNo(),
                callback.amountCents(), Instant.now());
        order.appendCallbackRecord(record);
        // 4. 防线二状态迁移（守卫判定顺序见 PaymentOrder 类 javadoc）——迁移
        //    守卫被拒（status_illegal/callback_duplicate/amount_mismatch）：
        //    先落库留痕再原样透传（B8.2 幂等命中不重放编排）
        if (callback.result() == GatewayResult.SUCCESS) {
            try {
                order.markPaid(callback.channelTxnNo(), callback.amountCents());
            } catch (final BusinessException e) {
                repository.save(order);
                throw e;
            }
            // 5. 成功编排（首次迁移成功后，同事务）：提权写段（TD-12——回调
            //    上下文无买家身份、tenant 空，推进店铺数据必须放行）内依序
            //    onPaid(主单) → 逐子单 confirmDeduct（编排序钉死，决策 2）
            try {
                tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                    orderFacade.onPaid(order.getOrderId());
                    confirmDeductPerSubOrder(order.getOrderId());
                    return null;
                });
            } catch (final RuntimeException e) {
                // 编排异常（聚合守卫/库存扣减失败）原样透传 → 方法事务整体
                // 回滚（TD-07 三域原子）；不单独落库（决策 6）
                throw e;
            } catch (final Exception e) {
                throw new IllegalStateException("支付回调编排提权事务失败", e);
            }
        } else {
            // 7. 失败回调：仅 markFailed——订单停留待支付等待超时关单（B8.3），
            //    不推进订单/库存
            try {
                order.markFailed(callback.channelTxnNo(), callback.amountCents());
            } catch (final BusinessException e) {
                repository.save(order);
                throw e;
            }
        }
        // 6. 落库：迁移 + 留痕经 save 持久化（与成功编排同一方法事务)
        repository.save(order);
    }

    /**
     * 逐子单确认扣减（I4）：子单订单项快照装配扣减明细（SKU + 数量，与
     * 下单预占/取消回滚的 items 装配同源对称）→ 逐子单
     * {@link InventoryFacade#confirmDeduct}（幂等键 (order_id, sku_id,
     * type) 操作单元 = 子单）；onPaid 已先行完成全部子单推进（编排序
     * 钉死：订单侧全推进先于任何扣减开始）。
     *
     * @param masterOrderId 主订单 ID（子单按 master_order_id 反查）
     */
    private void confirmDeductPerSubOrder(Long masterOrderId) {
        final List<SubOrder> subOrders =
                subOrderRepository.getByMasterOrderId(masterOrderId);
        for (final SubOrder subOrder : subOrders) {
            final List<StockChangeItem> items = subOrder.getItems().stream()
                    .map(item -> new StockChangeItem(item.getSkuId(), item.getQuantity()))
                    .toList();
            inventoryFacade.confirmDeduct(subOrder.getId(), items);
        }
    }
}