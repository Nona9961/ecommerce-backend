package com.nona.application.support;

import com.nona.domain.logistics.ports.WaybillDelivered;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderCompleted;
import com.nona.domain.order.ports.OrderCompletedEventPublisher;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.inf.context.TenantPrivilege;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;

/**
 * 签收自动完成消费方（logistics 签收事件 → order 自动完成联动）：
 * 运单签收（货物已妥投）后，系统代表买家触发订单侧完成推进——子单
 * 已发货 → 已完成 + 主单派生 + 完成事件发布，与买家确认收货（B9.3）
 * 共用同一完成迁移，触发源由调用方语义区分（B9.4③ 收货超时由超时
 * 调度引擎触发，本类只承载签收触发面）。
 * <p>
 * 装配形态（对齐跨域事件消费链路契约）：
 * <ul>
 *     <li>订阅：{@link TransactionalEventListener} AFTER_COMMIT 监听
 *         推进事务提交后的 {@link WaybillDelivered}（消费方只见已提交
 *         的签收状态——自动完成联动为最终一致联动，延迟可接受）；与
 *         签收事件日志消费（{@code WaybillDeliveredLogListener}）并存
 *         ——同一事件多监听器天然支持，互不干扰；</li>
 *     <li>异步：监听方法把自动完成任务提交到物流事件执行器
 *         （{@code waybillEventExecutor}——上下文传播装饰器随任务携带
 *         发布线程的租户快照；任务内编排自管事务与放行，不依赖请求
 *         上下文）；</li>
 *     <li>消费失败不影响物流主链路（任务体双层容错，同既有跨域消费
 *         形制）。</li>
 * </ul>
 * 编排语义（本类 javadoc 即契约）：
 * <ol>
 *     <li><b>装载</b>：按 subOrderId 装载子单（事件载荷操作单元）——
 *         不存在 → <b>静默跳过</b>（事件消费为最终一致联动：签收滞后/
 *         数据清理/子单不可见均属常态，静默防重复消费与乱序伤害）；</li>
 *     <li><b>幂等短路</b>：子单已 {@code COMPLETED} → 直接返回且不
 *         <b>重复发布完成事件</b>——模拟推进器终态闭合（每运单至多
 *         一次签收事件）之外的第二道防线：异常重试/重扫产生的重复
 *         签收事件、与买家确认/收货超时触发的完成竞态，均由短路兜底
 *         （完成迁移同一、完成事件子单粒度每次完成一次——与确认收货
 *         编排短路语义一致）；</li>
 *     <li><b>订单侧推进</b>：{@link OrderFacade#autoComplete}——子单
 *         {@code markCompleted}（仅已发货可完成；未发货直接完成/重复
 *         完成为非法迁移，聚合守卫拒绝——签收到达非已发货子单属数据
 *         异常，异常透传、事务回滚、任务体告警，事件消费不重试）+
 *         主单按全部子单投影派生；</li>
 *     <li><b>完成事件</b>：推进成功后发布 {@link OrderCompleted}
 *         （载荷 = subOrderId + 归属主单 ID——经装载子单反查）——与
 *         确认/超时共用的完成事件发布语义（子单粒度、每次完成一次，
 *         AFTER_COMMIT 投递，Phase-II 消费位同一机制）；</li>
 *     <li><b>提权写段</b>：跨租户写（子单 tenant=shopId 状态推进）在
 *         {@link TenantPrivilege#elevatedInTransaction} 内整体执行——
 *         监听线程无请求视角（系统触发），写 tenant 表须提权 + PO
 *         租户由写门禁注入/显式承载（TD-12，读放行与写放行合一）。
 *     </li>
 * </ol>
 * 装配声明：本类依赖订单侧仓储（子单装载）与订单门面——仓储 JPA
 * 实现未接线阶段的装配纪律同跨域用例先例：Spring 注册（{@code @Service}
 * 恢复）随接线阶段落位；单测以构造器直接装配。
 * <p>
 * 与买家确认/超时的关系：签收联动是系统侧自动完成的第三个触发源
 * （买家主动确认 / 收货超时 / 运单签收），三者操作同一完成迁移并
 * 共享短路语义，「先到先得、后到短路」——任一触发源完成子单后，其余
 * 触发源到达即幂等跳过，不产生重复推进、不重复发布完成事件。
 *
 * @author nona9961
 */
@Service
@Slf4j
public class WaybillDeliveredReceiptListener {

    /**
     * 事件日志统一前缀（定位标识：签收联动日志检索面）
     */
    private static final String LOG_PREFIX = "[waybill-receipt]";

    /**
     * 子订单仓储（签收联动操作单元装载 + 完成短路判定）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 订单门面（完成推进：子单 markCompleted + 主单派生）
     */
    private final OrderFacade orderFacade;

    /**
     * 完成事件发布端口（子单完成推进成功后发布，AFTER_COMMIT 投递）
     */
    private final OrderCompletedEventPublisher orderCompletedEventPublisher;

    /**
     * 提权工具（监听线程无请求视角——跨租户写段 elevatedInTransaction）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 事件异步执行器（物流事件执行器——与日志消费共用装配点与上下文
     * 传播装饰器）
     */
    private final Executor waybillEventExecutor;

    /**
     * 构造签收自动完成消费方。
     *
     * @param subOrderRepository         子订单仓储
     * @param orderFacade                订单门面
     * @param orderCompletedEventPublisher 完成事件发布端口
     * @param tenantPrivilege            提权工具
     * @param transactionTemplate        事务模板
     * @param waybillEventExecutor       物流事件异步执行器
     */
    public WaybillDeliveredReceiptListener(SubOrderRepository subOrderRepository,
                                           OrderFacade orderFacade,
                                           OrderCompletedEventPublisher orderCompletedEventPublisher,
                                           TenantPrivilege tenantPrivilege,
                                           TransactionTemplate transactionTemplate,
                                           @Qualifier("waybillEventExecutor") Executor waybillEventExecutor) {
        this.subOrderRepository = subOrderRepository;
        this.orderFacade = orderFacade;
        this.orderCompletedEventPublisher = orderCompletedEventPublisher;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
        this.waybillEventExecutor = waybillEventExecutor;
    }

    /**
     * 签收事件自动完成消费（事务提交后投递——无事务的发布路径不触发）：
     * 提交自动完成任务到事件执行器异步执行；提交/执行失败均捕获为
     * 告警，不影响物流主链路。
     *
     * @param event 签收事件（载荷 = 签收运单 + 关联子单）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWaybillDelivered(WaybillDelivered event) {
        final Long subOrderId = event.getPayload().subOrderId();
        try {
            waybillEventExecutor.execute(() -> {
                try {
                    autoCompleteOnDelivered(subOrderId);
                } catch (final RuntimeException ex) {
                    log.warn(LOG_PREFIX + " async consume failed: subOrderId={}, msg={}",
                            subOrderId, ex.getMessage(), ex);
                }
            });
        } catch (final RuntimeException ex) {
            log.warn(LOG_PREFIX + " async submit failed: subOrderId={}, msg={}",
                    subOrderId, ex.getMessage(), ex);
        }
    }

    /**
     * 签收自动完成编排（语义见类注）：装载子单 → 已完成幂等短路 →
     * 提权事务内订单完成推进 + 完成事件发布——整体在提权事务内
     * （监听线程无请求视角，读放行与写放行合一；事务回滚由异常透传
     * 承载）。
     *
     * @param subOrderId 签收运单关联子单 ID（事件载荷，必填）
     */
    public void autoCompleteOnDelivered(Long subOrderId) {
        if (subOrderId == null) {
            return;
        }
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
                if (subOrder == null) {
                    return null;
                }
                if (subOrder.getStatus() == SubOrderStatus.COMPLETED) {
                    return null;
                }
                orderFacade.autoComplete(subOrderId);
                orderCompletedEventPublisher.publishOrderCompleted(
                        new OrderCompleted(subOrderId, subOrder.getMasterOrderId()));
                return null;
            });
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("签收自动完成提权事务失败", e);
        }
    }
}