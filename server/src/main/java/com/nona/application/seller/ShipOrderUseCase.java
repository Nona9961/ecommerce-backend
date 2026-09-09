package com.nona.application.seller;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.factory.WaybillFactory;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 商家发货编排用例（S10.3 商家标记发货：录入承运公司与运单号，
 * 跨上下文同事务：物流运单创建 + 订单子单发货推进——order/logistics
 * 两域原子，与取消/完成/退款编排共享「归属校验 → 幂等短路 → 提权写段」
 * 形态，为第五个同构编排）。
 * <p>
 * <b>编排序</b>（设计 4.5 钉死：logistics.createWaybill + order.markShipped
 * 同事务；发货不涉及库存动作——预占扣减已由支付回调整单完成，
 * {@code confirmDeduct} 无需本编排参与）：
 * <ol>
 *     <li><b>入口装载</b>：按 subOrderId 装载子单——不存在 →
 *         {@code order.sub_not_found}（404，数据异常防御，不静默；
 *         CancelOrderUseCase 同先例）；</li>
 *     <li><b>商家归属校验</b>：子单归属店铺与操作者店铺（shopId，认证
 *         上下文定位，InventoryUseCase 同先例）不符 → <b>按不存在呈现</b>
 *         （{@code order.sub_not_found} 404，防越权与存在性泄露）——
 *         租户过滤（fail-closed）兜底先行，本校验为提权/装配路径第二
 *         道防线；聚合方法 {@code markShipped} 归属守卫
 *         （{@code order.sub_shop_mismatch} 403）为第三道；</li>
 *     <li><b>幂等短路</b>：目标子单状态已为 {@code SHIPPED} → 直接返回
 *         成功——商家重复点击发货/请求重放的常态路径，不重复创建运单
 *         不推进订单（ConfirmReceiptUseCase 已完成短路同构；短路以
 *         <b>子单</b>为判定单元——操作单元即子单，主单 SHIPPED 是全部
 *         子单发货的派生结果，子单判定自然覆盖主单级幂等面）；</li>
 *     <li><b>在途运单前置守卫</b>：{@link WaybillRepository#findInTransitBySubOrderId}
 *         命中在途运单 → {@code logistics.sub_order_conflict}（409，「一
 *         子单一在途」不变量拒绝重复发货）——短路之外的第二道在途防线
 *         （子单正常路径下「发货」与「子单 SHIPPED」同事务原子，重放命
 *         中短路；命中本守卫属数据异常/并发窗口防御）；并发窗口由
 *         waybill.sub_order_id 在途唯一约束（数据库兜底，绿阶段落位）；</li>
 *     <li><b>提权写段</b>：跨租户写（子单 tenant=shopId 状态推进 + 运单
 *         global 创建，设计 3.2 下单编排同构——跨上下文写统一收纳在
 *         {@link TenantPrivilege#elevatedInTransaction} 内整体执行）依序：
 *         ① {@link WaybillFactory#createWaybill}（承运公司/运单号非空
 *         守卫 + 状态定型待发货 + 初始轨迹装配，时间 = 编排当前时刻）；
 *         ② {@link WaybillRepository#save} 运单落库（waybill 主表 +
 *         waybill_track 从表）；③ {@link OrderFacade#markShipped}（签名
 *         冻结双参：subOrderId + waybillId 引用——聚合守卫内建：操作者
 *         店铺归属 403 / 运单必填 / 仅已支付可发货；子单 → 已发货 +
 *         运单引用定型 + 主单按子单投影派生——全部已发货 → 主单已发货、
 *         部分发货 → 部分发货）+ 同批保存；任一步失败 → 异常原样透传 →
 *         方法事务<b>整体回滚</b>（不出现「运单已建但子单未推进」的
 *         半程态）。</li>
 * </ol>
 * <p>
 * 事务边界 = 用例方法（方法级 {@link Transactional}）；领域方法不做事务。
 * 租户纪律：写放行（elevatedInTransaction）只出现在本类（application
 * 层），domain 内不放行；读放行本用例不需要（子单读在商家租户过滤面，
 * 运单/在途查询为 global 表无需放行，均处于方法事务内）。
 * <p>
 * 承载位依据：S10.3 为商家视角故事，归属校验以商家店铺为锚点（与
 * 买家视角编排的 buyerId 归属校验对称），故落 application.seller；
 * 跨上下文协作全经各域端口（WaybillFactory/WaybillRepository/OrderFacade）
 * 与仓储契约，应用层承载事务边界与提权写段（TD-12 写放行只允许出现在
 * application 层用例方法上）。
 * <p>
 * 装配声明：用例类<b>不注册为容器 bean</b>——Waybill 仓储 JPA 实现与
 * 订单侧端口/仓储实现未接线（红阶段装配学习，同取消/完成/退款编排
 * 用例先例）；以构造器注入声明装配契约，Spring 注册（{@code @Service}）
 * 随接线阶段落位恢复；单测以构造器直接装配。
 *
 * @author nona9961
 */
@Service
public class ShipOrderUseCase {

    /**
     * 子订单仓储（操作单元装载：入口守卫 + 归属校验锚点 + 短路判定）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 运单仓储（在途唯一前置守卫查询 + 运单落库）
     */
    private final WaybillRepository waybillRepository;

    /**
     * 运单工厂（运单创建：ID 生成 + 初始轨迹装配）
     */
    private final WaybillFactory waybillFactory;

    /**
     * 订单门面（子单发货推进 + 主单派生，订单侧状态迁移统一入口）
     */
    private final OrderFacade orderFacade;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排，与方法级事务同边界）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造商家发货编排用例。
     *
     * @param subOrderRepository  子订单仓储
     * @param waybillRepository   运单仓储
     * @param waybillFactory      运单工厂
     * @param orderFacade         订单门面
     * @param tenantPrivilege     提权工具
     * @param transactionTemplate 事务模板
     */
    public ShipOrderUseCase(SubOrderRepository subOrderRepository,
                            WaybillRepository waybillRepository,
                            WaybillFactory waybillFactory,
                            OrderFacade orderFacade,
                            TenantPrivilege tenantPrivilege,
                            TransactionTemplate transactionTemplate) {
        this.subOrderRepository = subOrderRepository;
        this.waybillRepository = waybillRepository;
        this.waybillFactory = waybillFactory;
        this.orderFacade = orderFacade;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 商家标记发货（S10.3：已支付子单 → 已发货 + 运单创建，跨上下文
     * 同事务；编排序见类 javadoc——归属校验 → 幂等短路 → 在途守卫 →
     * 提权写段内建单 + 落库 + 订单推进）。
     * <p>
     * 承运公司/运单号空白守卫由 {@link WaybillFactory} 承载（非空设计：
     * 工厂装配守卫 {@code logistics.company_blank} /
     * {@code logistics.tracking_no_blank}，本编排不重复校验）；重复发货
     * 拒绝面：子单已发货幂等短路 + 在途运单 409 + 聚合守卫非法迁移
     * 三重防线。
     *
     * @param shopId     操作者店铺 ID（认证上下文定位，归属校验锚点）
     * @param subOrderId 子订单 ID（必填）
     * @param company    承运公司（必填非空，运单创建定型）
     * @param trackingNo 运单号（必填非空，运单创建定型）
     */
    @Transactional
    public void shipByMerchant(Long shopId, Long subOrderId, String company,
                               String trackingNo) {
        // ① 入口装载：按 subOrderId 装载子单——不存在按不存在呈现 404
        // （数据异常防御，不静默，CancelOrderUseCase 同先例）
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        // ② 商家归属校验：子单归属店铺与操作者店铺不符 → 按不存在呈现
        // 404（防越权与存在性泄露——租户过滤 fail-closed 兜底先行，本
        // 校验为第二道防线；聚合守卫 sub_shop_mismatch 403 为第三道）
        if (!Objects.equals(subOrder.getShopId(), shopId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在或归属不符", 404);
        }
        // ③ 幂等短路：子单已 SHIPPED → 直接返回成功（重复点击/请求重放
        // 常态路径，不查在途/不建单/不推进；短路以子单为判定单元，主单
        // SHIPPED 是全部子单发货的派生结果，子单判定自然覆盖主单级幂等面）
        if (subOrder.getStatus() == SubOrderStatus.SHIPPED) {
            return;
        }
        // ④ 在途运单前置守卫：命中在途运单 → 409 拒绝（「一子单一在途」
        // 不变量；短路之外的第二道在途防线——并发窗口由 DB 在途唯一
        // 约束兜底；waybill 为 global 表，读无需放行）
        if (waybillRepository.findInTransitBySubOrderId(subOrderId).isPresent()) {
            throw new BusinessException(EcommerceBusinessCode.LOGISTICS_SUB_ORDER_CONFLICT.code(),
                    "子订单存在在途运单，重复发货被拒绝", 409);
        }
        // ⑤ 提权写段：跨租户写（子单 tenant=shopId 状态推进 + 运单 global
        // 创建）在 elevatedInTransaction 内整体执行——任一失败异常原样
        // 透传 → 方法级事务整体回滚（不出现「运单已建但子单未推进」半程态）
        try {
            tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final Waybill waybill = waybillFactory.createWaybill(subOrderId,
                        company, trackingNo, LocalDateTime.now());
                waybillRepository.save(waybill);
                orderFacade.markShipped(subOrderId, waybill.getId());
                return null;
            });
        } catch (final RuntimeException e) {
            // 业务异常（含聚合守卫拒绝/工厂守卫拒绝/落库失败）原样透传 →
            // 方法级 @Transactional 整体回滚，杜绝半程副作用
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("发货编排提权事务失败", e);
        }
    }
}
