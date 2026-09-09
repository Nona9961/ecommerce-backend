package com.nona.application.mall;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.MallOrderStatus;
import com.nona.api.mall.OrderView;
import com.nona.api.mall.WaybillView;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 买家订单查询用例（B9.1/B9.2/B10.1 承载：GET /mall/orders 列表、
 * GET /mall/orders/{masterOrderId} 详情、GET /mall/sub-orders/
 * {subOrderId}/waybill 运单——WU-59 冻结，形状钉死前端 WU-44 约定
 * 清单，消费 WU-55 冻结仓储查询契约，零签名变更）。
 * <p>
 * 编排语义（只读面：分页行不登记变更追踪——55 冻结「读取面纪律」）：
 * <ol>
 *     <li><b>列表</b>：状态 tab → 仓储过滤集合映射（见下，前端
 *         ORDER_TABS 语义收敛点）+ 分页（PageQuery 归一化后 offset/
 *         limit 透传）；未命中返回空列表，total=0；</li>
 *     <li><b>详情</b>：按 masterOrderId 装载主单——不存在或归属买家
 *         不符 → 按不存在呈现（{@code order.master_not_found} 404，
 *         防越权与存在性泄露，PaymentUseCase 同先例）→ 子单集合按
 *         master_order_id 反查（保持创建序）→ 店铺名按 shopId 装载
 *         （ShopRepository.getByID，同单内去重）→ 待支付主单装配
 *         支付单视图（PaymentOrderRepository.findByOrderId——非待支付
 *         不装配，payment=null）；</li>
 *     <li><b>运单</b>：按 subOrderId 装载子单——不存在或归属买家不符 →
 *         {@code order.sub_not_found} 404（RefundUseCase 同先例）→
 *         WaybillRepository.findBySubOrderId 装载最新运单（任意状态含
 *         签收终态）——不存在 → {@code logistics.not_found} 404 →
 *         运单轨迹 + 子单商品行/店铺名装配。</li>
 * </ol>
 * <b>状态 tab → 过滤集合映射（冻结，前端 ORDER_TABS 六态 + 全部）</b>：
 * <ul>
 *     <li>null（「全部」tab 不传 status）= 不过滤（仓储 null/空集合语义）</li>
 *     <li>{@code PENDING_PAYMENT} → {PENDING_PAYMENT}</li>
 *     <li>{@code PAID} → {PAID}</li>
 *     <li>{@code SHIPPED} → {SHIPPED}</li>
 *     <li>{@code COMPLETED} → {COMPLETED}</li>
 *     <li>{@code CANCELLED} → {CANCELLED}</li>
 *     <li>{@code REFUNDED} → {REFUNDED}</li>
 * </ul>
 * {@code PARTIALLY_SHIPPED / REFUNDING / CLOSED} 不进 tab（前端归
 * 「全部」，订单视图正常呈现）；非法 tab 值由 controller 层
 * {@link MallOrderStatus#fromName} 400 拒绝（本用例只接受已解析枚举）。
 * <p>
 * 装配说明（红阶段）：本类为 {@code @Service} 骨架占位（UOE 方法体——
 * WU-55 五仓储骨架同款：新建类骨架挂注册注解 + 方法体占位，绿阶段只
 * 实现方法体，注解保留）；容器可启动但本用例行为未接线。
 *
 * @author nona9961
 */
@Service
public class BuyerOrderQuery {

    /**
     * 主订单仓储（买家分页/计数 + 按 ID 装载）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 子订单仓储（主单反查 + 按 ID 装载）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 运单仓储（按子单装载）
     */
    private final WaybillRepository waybillRepository;

    /**
     * 支付单仓储（待支付视图装配）
     */
    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * 店铺仓储（子单店铺名装配）
     */
    private final ShopRepository shopRepository;

    /**
     * 构造买家订单查询用例。
     *
     * @param masterOrderRepository  主订单仓储（必填）
     * @param subOrderRepository     子订单仓储（必填）
     * @param waybillRepository      运单仓储（必填）
     * @param paymentOrderRepository 支付单仓储（必填）
     * @param shopRepository         店铺仓储（必填）
     */
    public BuyerOrderQuery(MasterOrderRepository masterOrderRepository,
                           SubOrderRepository subOrderRepository,
                           WaybillRepository waybillRepository,
                           PaymentOrderRepository paymentOrderRepository,
                           ShopRepository shopRepository) {
        this.masterOrderRepository = masterOrderRepository;
        this.subOrderRepository = subOrderRepository;
        this.waybillRepository = waybillRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.shopRepository = shopRepository;
    }

    /**
     * 买家订单分页列表（B9.1；映射语义见类 javadoc，只读编排）。
     *
     * @param buyerId 买家账号 ID（必填）
     * @param tab     状态 tab（null = 全部不过滤；已解析枚举）
     * @param query   分页请求（PageQuery 归一化）
     * @return 分页订单视图（创建时间倒序）；无命中为空列表 + total 0
     */
    public PageResult<OrderView> listPaged(Long buyerId, MallOrderStatus tab, PageQuery query) {
        throw new UnsupportedOperationException(
                "listPaged 未实现：红阶段契约占位，绿阶段实现（tab 映射 + 分页 + 视图装配）");
    }

    /**
     * 买家订单详情（B9.2；不存在或归属不符按不存在呈现 404）。
     *
     * @param buyerId       买家账号 ID（归属校验）
     * @param masterOrderId 主订单 ID
     * @return 订单视图（子单/金额/地址/待支付支付单）
     */
    public OrderView detail(Long buyerId, Long masterOrderId) {
        throw new UnsupportedOperationException(
                "detail 未实现：红阶段契约占位，绿阶段实现（归属校验 + 装配）");
    }

    /**
     * 买家运单视图（B10.1；子单不存在/归属不符 → order.sub_not_found
     * 404；无运单 → logistics.not_found 404）。
     *
     * @param buyerId    买家账号 ID（归属校验）
     * @param subOrderId 子订单 ID
     * @return 运单视图（轨迹 + 子单商品行/店铺名装配）
     */
    public WaybillView waybill(Long buyerId, Long subOrderId) {
        throw new UnsupportedOperationException(
                "waybill 未实现：红阶段契约占位，绿阶段实现（归属校验 + 装配）");
    }
}