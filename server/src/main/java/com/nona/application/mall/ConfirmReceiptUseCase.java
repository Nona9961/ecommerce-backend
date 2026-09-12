package com.nona.application.mall;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderCompleted;
import com.nona.domain.order.ports.OrderCompletedEventPublisher;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.context.TenantPrivilege;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 确认收货编排用例（买家主动确认收货 + 收货超时自动完成，
 * 订单侧推进：子单已发货 → 已完成 + 主单派生；完成事件 OrderCompleted
 * 从本编排发布——子单完成粒度，AFTER_COMMIT 投递，现行为日志型消费）。
 * <p>
 * <b>编排序</b>（收货超时 = 超时调度自动确认，与
 * 买家主动确认共用同一完成迁移——聚合侧 {@code markCompleted} 触发源
 * 由调用方语义区分，与取消编排「买家 + 超时共用共享编排」同构）：
 * <ol>
 *     <li><b>买家入口（confirmByBuyer）</b>：按 subOrderId 装载子单——
 *         不存在 → 按不存在呈现（{@code order.sub_not_found} 404）；
 *         经子单归属主单反查主单并校验买家归属——主单不存在（数据异常
 *         防御）或归属买家不符 → 按不存在呈现（{@code order.master_not_found}
 *         404，防越权与存在性泄露，CancelOrderUseCase 同先例）；
 *         超时入口（autoCompleteByTimeout）为系统调度触发（无买家身份），
 *         子单不存在同样 404（数据异常防御，不静默）；</li>
 *     <li><b>幂等短路</b>：目标子单状态已为 {@code COMPLETED}（完成
 *         迁移终态）→ 直接返回成功且<b>不重复发布完成事件</b>——
 *         买家确认重放与超时调度重扫（handler 幂等）的常态
 *         路径；短路以<b>子单</b>为判定单元（操作单元即子单，主单
 *         COMPLETED 是全部子单完成的派生结果，子单判定自然覆盖主单
 *         级幂等面）；短路的并发窗口由数据库层兜底（与取消编排对齐）；</li>
 *     <li><b>归属校验先于幂等短路</b>：他人对已完成子单的确认同样按
 *         不存在拒绝（不因幂等短路而放行越权请求）；</li>
 *     <li><b>提权写段</b>：跨租户写（子单 tenant=shopId 状态推进）在
 *         {@link TenantPrivilege#elevatedInTransaction} 内整体执行——
 *         任一失败整体回滚（方法级 {@link Transactional} 统辖），买家
 *         视角推进店铺数据必须提权（取消编排同构）；</li>
 *     <li><b>订单侧推进</b>：{@link OrderFacade#autoComplete}（签名
 *         冻结，双入口共用）——子单 {@code markCompleted}（仅
 *         已发货可完成；未发货直接完成/重复完成为非法迁移，聚合守卫
 *         拒绝 {@code order.sub_status_illegal}）+ 主单按全部子单投影
 *         派生（全部完成 → 主单已完成）；</li>
 *     <li><b>完成事件（OrderCompleted）</b>：子单完成推进成功后发布
 *         ——<b>子单粒度、每次完成一次</b>（主单完成 = 派生投影非独立
 *         迁移，最后一个子单完成的发布时点即主单完成时点；多子单
 *         部分完成属正常中间态，消费方按子单处理结算/评价资格）；
 *         事件载荷 = subOrderId + masterOrderId（最小定位引用）；
 *         AFTER_COMMIT 异步监听，现行为日志型消费，消费位（评价
 *         资格授予/结算入账/通知/统计）由对应域独立接入，发布失败
 *         同事务回滚（事件不承担可靠性职责，可靠面由同库事务承担）。</li>
 * </ol>
 * <b>超时复用面（冻结）</b>：收货超时 handler（消费编排 WU）以
 * {@link #autoCompleteByTimeout} 为唯一入口复用本编排（含完成事件
 * 发布），不感知内部细节；买家入口保留归属校验，超时入口无身份
 * 校验。
 * <p>
 * 事务边界 = 用例方法（方法级 {@link Transactional}）；领域方法不做
 * 事务。租户纪律：写放行（elevatedInTransaction）只出现在本类
 * （application 层），domain 内不放行；读放行（{@code @CrossTenant}）
 * 只出现在本类入口方法——子单/主单装载面（tenant-scoped 子单在买家/
 * 调度上下文无视角时被租户过滤置空 → 404 误报），方法级读放行罩住
 * 前置装载段（写段保持 elevatedInTransaction 门禁，注解不影响写门禁——
 * CrossTenantAspect 语义），CancelOrderUseCase 先例同形。
 * <p>
 * 装配声明：本类注册为容器 bean（{@code @Service}）——订单侧端口
 * 实现与 MasterOrder/SubOrder 仓储实现已接线；以构造器注入声明装配
 * 契约，单测以构造器直接装配。
 *
 * @author nona9961
 */
@Service
public class ConfirmReceiptUseCase {

    /**
     * 主订单仓储（买家确认入口的归属校验：子单反查主单 + 买家匹配）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 子订单仓储（操作单元装载：短路判定 + 超时入口装载）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 订单门面（订单侧完成推进与主单派生，双入口共用）
     */
    private final OrderFacade orderFacade;

    /**
     * 完成事件发布端口（子单完成推进成功后发布，AFTER_COMMIT 投递）
     */
    private final OrderCompletedEventPublisher orderCompletedEventPublisher;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造确认收货编排用例。
     *
     * @param masterOrderRepository       主订单仓储
     * @param subOrderRepository          子订单仓储
     * @param orderFacade                 订单门面
     * @param orderCompletedEventPublisher 完成事件发布端口
     * @param tenantPrivilege             提权工具
     * @param transactionTemplate         事务模板
     */
    public ConfirmReceiptUseCase(MasterOrderRepository masterOrderRepository,
                                 SubOrderRepository subOrderRepository,
                                 OrderFacade orderFacade,
                                 OrderCompletedEventPublisher orderCompletedEventPublisher,
                                 TenantPrivilege tenantPrivilege,
                                 TransactionTemplate transactionTemplate) {
        this.masterOrderRepository = masterOrderRepository;
        this.subOrderRepository = subOrderRepository;
        this.orderFacade = orderFacade;
        this.orderCompletedEventPublisher = orderCompletedEventPublisher;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 买家主动确认收货（已发货子单 → 已完成 + 主单派生 + 完成
     * 事件发布）。
     * <p>
     * 归属校验先于幂等短路：子单不存在 → 404（order.sub_not_found —
     * 按不存在呈现）；主单不存在或归属买家不符 → 404
     * （order.master_not_found — 数据异常防御与防越权/防存在性泄露）；
     * 校验通过后复用共享编排 {@link #completeInternal}（含幂等短路）。
     *
     * @param buyerId   当前买家账号 ID（认证上下文，归属校验锚点）
     * @param subOrderId 子订单 ID（必填）
     */
    @CrossTenant
    @Transactional
    public void confirmByBuyer(Long buyerId, Long subOrderId) {
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        final MasterOrder masterOrder =
                masterOrderRepository.getByID(subOrder.getMasterOrderId());
        if (masterOrder == null || !Objects.equals(masterOrder.getBuyerId(), buyerId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在或归属不符", 404);
        }
        completeInternal(subOrderId, subOrder.getStatus(), subOrder.getMasterOrderId());
    }

    /**
     * 收货超时自动完成（逾期未确认自动确认收货完成；调度引擎/
     * handler 唯一复用入口，含完成事件发布）。
     * <p>
     * 系统触发无买家身份，不做归属校验；子单不存在 → 404（数据异常
     * 防御，不静默）；子单已完成 → 幂等短路成功且不重复发布事件
     * （超时重扫常态路径）。
     *
     * @param subOrderId 子订单 ID（必填）
     */
    @CrossTenant
    @Transactional
    public void autoCompleteByTimeout(Long subOrderId) {
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        completeInternal(subOrderId, subOrder.getStatus(), subOrder.getMasterOrderId());
    }

    /**
     * 共享完成编排（编排序见类 javadoc；幂等短路 → 提权写段内：
     * orderFacade.autoComplete → 发布 OrderCompleted）。
     * <p>
     * 幂等短路以入口装载的目标子单状态判定（操作单元即子单；已完成
     * 子单直返成功）；短路成功后不触发任何写动作且<b>不重复发布
     * 完成事件</b>（幂等重放/超时重扫不产生重复事件，消费
     * 方不收重复完成）。
     *
     * @param subOrderId    目标子订单 ID
     * @param subStatus     目标子单当前状态（入口装载值，短路判定依据）
     * @param masterOrderId 目标子单归属主单 ID（事件载荷组装）
     */
    private void completeInternal(Long subOrderId, SubOrderStatus subStatus,
                                  Long masterOrderId) {
        if (subStatus == SubOrderStatus.COMPLETED) {
            return;
        }
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                orderFacade.autoComplete(subOrderId);
                orderCompletedEventPublisher.publishOrderCompleted(
                        new OrderCompleted(subOrderId, masterOrderId));
                return null;
            });
        } catch (final RuntimeException e) {
            // 业务异常（聚合守卫拒绝/完成推进失败/发布失败）原样透传 →
            // 方法级 @Transactional 整体回滚，杜绝半程副作用
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("完成编排提权事务失败", e);
        }
    }
}