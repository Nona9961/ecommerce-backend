package com.nona.application.mall;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.mall.MallOrderStatus;
import com.nona.api.mall.MallWaybillStatus;
import com.nona.api.mall.OrderAddress;
import com.nona.api.mall.OrderItemView;
import com.nona.api.mall.OrderView;
import com.nona.api.mall.PaymentStatus;
import com.nona.api.mall.PaymentView;
import com.nona.api.mall.SubOrderView;
import com.nona.api.mall.WaybillTrackView;
import com.nona.api.mall.WaybillView;
import com.nona.domain.catalog.entity.Shop;
import com.nona.domain.catalog.repo.ShopRepository;
import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 买家订单查询用例（承载：GET /mall/orders 列表、
 * GET /mall/orders/{masterOrderId} 详情、GET /mall/sub-orders/
 * {subOrderId}/waybill 运单——契约冻结，形状钉死前端约定
 * 清单，消费冻结仓储查询契约，零签名变更）。
 * <p>
 * 编排语义（只读面：分页行不登记变更追踪——冻结「读取面纪律」）：
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
 * 「全部」，订单视图正常呈现），传参等价「全部」不过滤；非法 tab 值
 * 由 controller 层 {@link MallOrderStatus#fromName} 400 拒绝（本用例
 * 只接受已解析枚举）。
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
     * 买家订单分页列表（映射语义见类 javadoc，只读编排）。
     *
     * @param buyerId 买家账号 ID（必填）
     * @param tab     状态 tab（null = 全部不过滤；已解析枚举）
     * @param query   分页请求（PageQuery 归一化）
     * @return 分页订单视图（创建时间倒序）；无命中为空列表 + total 0
     */
    @CrossTenant
    public PageResult<OrderView> listPaged(Long buyerId, MallOrderStatus tab, PageQuery query) {
        final Collection<MasterOrderStatus> statuses = mapTab(tab);
        final List<MasterOrder> orders = masterOrderRepository.listPagedByBuyer(
                buyerId, statuses, Math.toIntExact(query.offset()), query.pageSize());
        final long total = masterOrderRepository.countByBuyer(buyerId, statuses);
        final List<OrderView> views = new ArrayList<>(orders.size());
        for (MasterOrder order : orders) {
            final List<SubOrder> subOrders = subOrderRepository.getByMasterOrderId(order.getId());
            views.add(toOrderView(order, subOrders, null));
        }
        return PageResult.of(views, total, query);
    }

    /**
     * 买家订单详情（不存在或归属不符按不存在呈现 404）。
     *
     * @param buyerId       买家账号 ID（归属校验）
     * @param masterOrderId 主订单 ID
     * @return 订单视图（子单/金额/地址/待支付支付单）
     */
    @CrossTenant
    public OrderView detail(Long buyerId, Long masterOrderId) {
        final MasterOrder order = masterOrderRepository.getByID(masterOrderId);
        if (order == null || !Objects.equals(buyerId, order.getBuyerId())) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在或不属于当前买家（order.master_not_found）");
        }
        final List<SubOrder> subOrders = subOrderRepository.getByMasterOrderId(masterOrderId);
        final PaymentView payment = order.getStatus() == MasterOrderStatus.PENDING_PAYMENT
                ? loadPaymentView(masterOrderId) : null;
        return toOrderView(order, subOrders, payment);
    }

    /**
     * 买家运单视图（子单不存在/归属不符 → order.sub_not_found
     * 404；无运单 → logistics.not_found 404）。
     *
     * @param buyerId    买家账号 ID（归属校验）
     * @param subOrderId 子订单 ID
     * @return 运单视图（轨迹 + 子单商品行/店铺名装配）
     */
    @CrossTenant
    public WaybillView waybill(Long buyerId, Long subOrderId) {
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null || !ownedByBuyer(subOrder, buyerId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在或不属于当前买家（order.sub_not_found）");
        }
        final Waybill waybill = waybillRepository.findBySubOrderId(subOrderId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.LOGISTICS_NOT_FOUND.code(),
                        "子订单不存在运单记录（logistics.not_found）"));
        final Shop shop = shopRepository.getByID(subOrder.getShopId());
        return new WaybillView(waybill.getId(), waybill.getSubOrderId(),
                waybill.getCompany(), waybill.getTrackingNo(),
                toMallWaybillStatus(waybill.getStatus()),
                waybill.getTracks().stream().map(BuyerOrderQuery::toWaybillTrackView).toList(),
                subOrder.getSubOrderNo(), subOrder.getShopId(),
                shop == null ? null : shop.getName(),
                subOrder.getItems().stream().map(BuyerOrderQuery::toOrderItemView).toList());
    }

    /**
     * 待支付主单装配支付单视图（非待支付不调用；装载为空 = 防御形态
     * 返回 null——上游下单编排保证待支付主单必有支付单）。
     *
     * @param masterOrderId 主订单 ID
     * @return 支付单视图；无支付单返回 null
     */
    private PaymentView loadPaymentView(Long masterOrderId) {
        final PaymentOrder paymentOrder = paymentOrderRepository.findByOrderId(masterOrderId);
        if (paymentOrder == null) {
            return null;
        }
        return new PaymentView(paymentOrder.getId(), paymentOrder.getPayNo(),
                paymentOrder.getAmount(),
                paymentOrder.getTimeoutAt() == null ? null : paymentOrder.getTimeoutAt().toString(),
                toPaymentStatus(paymentOrder.getStatus()));
    }

    /**
     * 子单归属校验（买家维度 fail-closed）：子单经归属主单反查买家——
     * 主单不存在（脏引用）或买家不符一律按不存在呈现。
     *
     * @param subOrder 子单（非 null）
     * @param buyerId  买家账号 ID
     * @return true = 归属当前买家
     */
    private boolean ownedByBuyer(SubOrder subOrder, Long buyerId) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(subOrder.getMasterOrderId());
        return masterOrder != null && Objects.equals(buyerId, masterOrder.getBuyerId());
    }

    /**
     * 状态 tab → 主单状态过滤集合（冻结映射，类 javadoc 固化）；
     * 未进 tab 的值（PARTIALLY_SHIPPED/REFUNDING/CLOSED）按「全部」
     * 不过滤呈现（前端归「全部」语义）。
     *
     * @param tab 已解析 tab 值（null = 全部）
     * @return 过滤集合；null = 不过滤
     */
    private static Collection<MasterOrderStatus> mapTab(MallOrderStatus tab) {
        if (tab == null) {
            return null;
        }
        return switch (tab) {
            case PENDING_PAYMENT -> List.of(MasterOrderStatus.PENDING_PAYMENT);
            case PAID -> List.of(MasterOrderStatus.PAID);
            case SHIPPED -> List.of(MasterOrderStatus.SHIPPED);
            case COMPLETED -> List.of(MasterOrderStatus.COMPLETED);
            case CANCELLED -> List.of(MasterOrderStatus.CANCELLED);
            case REFUNDED -> List.of(MasterOrderStatus.REFUNDED);
            case PARTIALLY_SHIPPED, REFUNDING, CLOSED -> null;
        };
    }

    /**
     * 主单 + 子单集合 → 订单视图（子单店铺名按 shopId 装载，同单内
     * 去重缓存）。
     *
     * @param order     主单（已归属校验）
     * @param subOrders 子单集合（保持创建序）
     * @param payment   支付单视图（非待支付传 null）
     * @return 订单视图
     */
    private OrderView toOrderView(MasterOrder order, List<SubOrder> subOrders,
                                  PaymentView payment) {
        final Map<Long, String> shopNames = new HashMap<>();
        final List<SubOrderView> subViews = new ArrayList<>(subOrders.size());
        for (SubOrder subOrder : subOrders) {
            final String shopName = shopNames.computeIfAbsent(subOrder.getShopId(), shopId -> {
                final Shop shop = shopRepository.getByID(shopId);
                return shop == null ? null : shop.getName();
            });
            subViews.add(toSubOrderView(subOrder, shopName));
        }
        final AddressSnapshot address = order.getAddress();
        return new OrderView(order.getId(), order.getOrderNo(),
                toMallOrderStatus(order.getStatus()),
                order.getCreatedAt() == null ? null : order.getCreatedAt().toString(),
                new OrderAddress(address.getRecipient(), address.getPhone(),
                        address.getProvince(), address.getCity(), address.getDistrict(),
                        address.getDetail()),
                order.getAmount().getGoodsAmount(), order.getAmount().getFreightAmount(),
                order.getAmount().getDiscount(), order.getAmount().getPaidAmount(),
                subViews, payment);
    }

    /**
     * 子单 → 子单视图（金额四维 + 商品快照行原样投影）。
     *
     * @param subOrder 子单
     * @param shopName 店铺名（装载异常为 null 防御形态）
     * @return 子单视图
     */
    private static SubOrderView toSubOrderView(SubOrder subOrder, String shopName) {
        return new SubOrderView(subOrder.getId(), subOrder.getSubOrderNo(),
                subOrder.getShopId(), shopName,
                toMallOrderStatus(subOrder.getStatus()), subOrder.getWaybillId(),
                subOrder.getAmount().getGoodsAmount(),
                subOrder.getAmount().getFreightAmount(),
                subOrder.getAmount().getDiscount(),
                subOrder.getAmount().getPaidAmount(),
                subOrder.getItems().stream().map(BuyerOrderQuery::toOrderItemView).toList());
    }

    /**
     * 订单项 → 订单项视图（快照行原样投影；规格/自定义属性 Map 保序
     * 透传）。
     *
     * @param item 订单项快照
     * @return 订单项视图
     */
    private static OrderItemView toOrderItemView(OrderItem item) {
        return new OrderItemView(item.getProductId(), item.getSkuId(),
                item.getProductName(), item.getUnitPrice(), item.getQuantity(),
                item.getSubtotal(), item.getMainImageUrl(), item.getSpecSummary(),
                item.getSpecAttributes(), item.getCustomAttributes());
    }

    /**
     * 运单轨迹 → 轨迹视图（occurredAt LocalDateTime → ISO-8601 字符串，
     * UTC 语义与 PO 列一致）。
     *
     * @param track 轨迹追加记录
     * @return 轨迹视图
     */
    private static WaybillTrackView toWaybillTrackView(WaybillTrack track) {
        return new WaybillTrackView(toMallWaybillStatus(track.getStatus()),
                track.getOccurredAt().toString(), track.getDescription());
    }

    /**
     * 主单状态 → 买家订单状态（9 值逐名对应，契约冻结映射）。
     *
     * @param status 主单派生状态
     * @return 线上契约枚举
     */
    private static MallOrderStatus toMallOrderStatus(MasterOrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> MallOrderStatus.PENDING_PAYMENT;
            case PAID -> MallOrderStatus.PAID;
            case PARTIALLY_SHIPPED -> MallOrderStatus.PARTIALLY_SHIPPED;
            case SHIPPED -> MallOrderStatus.SHIPPED;
            case COMPLETED -> MallOrderStatus.COMPLETED;
            case CANCELLED -> MallOrderStatus.CANCELLED;
            case REFUNDING -> MallOrderStatus.REFUNDING;
            case REFUNDED -> MallOrderStatus.REFUNDED;
            case CLOSED -> MallOrderStatus.CLOSED;
        };
    }

    /**
     * 子单状态 → 买家订单状态（8 值逐名对应，契约冻结映射）。
     *
     * @param status 子单履约状态
     * @return 线上契约枚举
     */
    private static MallOrderStatus toMallOrderStatus(SubOrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> MallOrderStatus.PENDING_PAYMENT;
            case PAID -> MallOrderStatus.PAID;
            case SHIPPED -> MallOrderStatus.SHIPPED;
            case COMPLETED -> MallOrderStatus.COMPLETED;
            case CANCELLED -> MallOrderStatus.CANCELLED;
            case REFUNDING -> MallOrderStatus.REFUNDING;
            case REFUNDED -> MallOrderStatus.REFUNDED;
            case CLOSED -> MallOrderStatus.CLOSED;
        };
    }

    /**
     * 运单状态 → 线上契约枚举（4 值逐名对应）。
     *
     * @param status 运单状态
     * @return 线上契约枚举
     */
    private static MallWaybillStatus toMallWaybillStatus(WaybillStatus status) {
        return switch (status) {
            case PENDING_SHIPMENT -> MallWaybillStatus.PENDING_SHIPMENT;
            case SHIPPED -> MallWaybillStatus.SHIPPED;
            case IN_TRANSIT -> MallWaybillStatus.IN_TRANSIT;
            case DELIVERED -> MallWaybillStatus.DELIVERED;
        };
    }

    /**
     * 支付单状态 → 线上契约枚举（4 值逐名对应）。
     *
     * @param status 支付单状态
     * @return 线上契约枚举
     */
    private static PaymentStatus toPaymentStatus(PaymentOrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> PaymentStatus.PENDING_PAYMENT;
            case PAID -> PaymentStatus.PAID;
            case FAILED -> PaymentStatus.FAILED;
            case CLOSED -> PaymentStatus.CLOSED;
        };
    }
}