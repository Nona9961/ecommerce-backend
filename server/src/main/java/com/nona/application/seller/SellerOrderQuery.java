package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.SellerOrderAddress;
import com.nona.api.seller.SellerOrderAmount;
import com.nona.api.seller.SellerOrderItemView;
import com.nona.api.seller.SellerSubOrderDetail;
import com.nona.api.seller.SellerSubOrderItem;
import com.nona.api.seller.SellerWaybillView;
import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 商家端本店订单查询用例（seller 面，WU-60 冻结；Spring 注册 @Service
 * 随绿阶段与实现同提交——55 同款纪律，避免无中间态启动）。
 * <p>
 * 编排语义（只读，无事务写面）：
 * <ul>
 *     <li><b>列表分页</b>：消费 55 冻结的 {@code SubOrderRepository
 *         .listPagedByShop/countByShop}（店铺显式条件 + 租户过滤双层
 *         fail-closed、状态多值过滤、创建时间倒序 + 主键倒序稳定分页），
 *         {@code statuses} null/空 = 全量（「全部」tab 语义收敛在本层）；
 *         行映射 = SubOrder → {@link SellerSubOrderItem}（金额分透传、
 *         状态枚举名、itemCount = 订单项数、firstImageUrl = 首项主图、
 *         createTime = 审计时间字符串投影——LocalDateTime.toString()，
 *         FavoriteItem 先例）；分页参数换算（offset = (pageNum-1) ×
 *         pageSize）经 {@link PageQuery#offset()} 承载；</li>
 *     <li><b>详情 + 运单概要</b>：按 ID 装载（租户过滤 fail-closed——
 *         不存在/跨店按 404 呈现，归属不泄露）+ 显式归属二道校验（店铺
 *         不符按不存在呈现，ShipOrderUseCase 同款）；运单概要 = 子单
 *         waybillId 非空时经 Waybill 聚合读（company/trackingNo/status
 *         枚举名），未发货（引用为空）为 null；运单引用悬挂（已发货但
 *         运单行缺失）按 null 概要容忍呈现（展示驱动，不阻断详情——
 *         数据异常防御面见装配清单）；</li>
 *     <li><b>createTime</b>：下单时间字段消费冻结契约补充
 *         （SubOrder 装载回填主表审计时间——详情/列表 DTO 输出侧）。</li>
 * </ul>
 *
 * @author nona9961
 */
public class SellerOrderQuery {

    /**
     * 子订单仓储（店铺分页/详情装载面 + 租户过滤）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 运单仓储（详情运单概要装载）
     */
    private final WaybillRepository waybillRepository;

    /**
     * 构造本店订单查询用例。
     *
     * @param subOrderRepository 子订单仓储
     * @param waybillRepository  运单仓储
     */
    public SellerOrderQuery(SubOrderRepository subOrderRepository,
                            WaybillRepository waybillRepository) {
        this.subOrderRepository = subOrderRepository;
        this.waybillRepository = waybillRepository;
    }

    /**
     * 本店子订单分页列表（创建时间倒序；状态多值过滤 null/空 = 全量）。
     *
     * @param shopId   当前店铺 ID（认证上下文定位，必填）
     * @param statuses 状态过滤集合（枚举；null/空 = 不过滤全量）
     * @param page     分页请求（pageNum/pageSize 已归一化）
     * @return 分页列表行（金额分；createTime 审计时间字符串投影）
     */
    public PageResult<SellerSubOrderItem> listPaged(Long shopId,
                                                    Collection<SubOrderStatus> statuses,
                                                    PageQuery page) {
        final List<SubOrder> rows = subOrderRepository.listPagedByShop(shopId, statuses,
                Math.toIntExact(page.offset()), page.pageSize());
        final long total = subOrderRepository.countByShop(shopId, statuses);
        return PageResult.of(rows.stream().map(SellerOrderQuery::toItem).toList(), total, page);
    }

    /**
     * 本店子订单详情（含运单概要）。
     *
     * @param shopId     当前店铺 ID（认证上下文定位，必填）
     * @param subOrderId 子订单 ID（不存在或跨店铺按 404 呈现）
     * @return 子订单详情（金额分；waybill 未发货为 null）
     */
    public SellerSubOrderDetail detail(Long shopId, Long subOrderId) {
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        // 显式归属二道校验（租户过滤兜底先行）：店铺不符按不存在呈现，归属不泄露
        if (!Objects.equals(subOrder.getShopId(), shopId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在或归属不符", 404);
        }
        final SellerOrderAddress address = toAddress(subOrder.getAddress());
        final SellerOrderAmount amount = toAmount(subOrder.getAmount());
        final List<SellerOrderItemView> items =
                subOrder.getItems().stream().map(SellerOrderQuery::toItemView).toList();
        return new SellerSubOrderDetail(
                subOrder.getId(),
                subOrder.getSubOrderNo(),
                subOrder.getMasterOrderId(),
                subOrder.getStatus().name(),
                subOrder.getShopId(),
                address, amount, items,
                toWaybill(subOrder.getWaybillId()),
                subOrder.getCreateTime() == null ? null : subOrder.getCreateTime().toString());
    }

    /**
     * 子订单 → 列表行（金额分透传、状态枚举名、itemCount = 订单项数、
     * firstImageUrl = 首项主图、createTime 审计时间字符串投影）。
     *
     * @param subOrder 子订单聚合
     * @return 列表行
     */
    private static SellerSubOrderItem toItem(SubOrder subOrder) {
        final List<OrderItem> items = subOrder.getItems();
        return new SellerSubOrderItem(
                subOrder.getId(),
                subOrder.getSubOrderNo(),
                subOrder.getMasterOrderId(),
                subOrder.getStatus().name(),
                subOrder.getAddress().getRecipient(),
                subOrder.getAmount().getGoodsAmount(),
                subOrder.getAmount().getFreightAmount(),
                subOrder.getAmount().getPaidAmount(),
                items.size(),
                items.isEmpty() ? null : items.get(0).getMainImageUrl(),
                subOrder.getCreateTime() == null ? null : subOrder.getCreateTime().toString());
    }

    /**
     * 地址快照 → 契约视图（六字段逐字段投影）。
     *
     * @param address 地址快照
     * @return 契约地址
     */
    private static SellerOrderAddress toAddress(AddressSnapshot address) {
        return new SellerOrderAddress(
                address.getRecipient(), address.getPhone(), address.getProvince(),
                address.getCity(), address.getDistrict(), address.getDetail());
    }

    /**
     * 金额明细 → 契约视图（金额分透传）。
     *
     * @param amount 金额明细
     * @return 契约金额
     */
    private static SellerOrderAmount toAmount(AmountDetail amount) {
        return new SellerOrderAmount(
                amount.getGoodsAmount(), amount.getFreightAmount(),
                amount.getDiscount(), amount.getPaidAmount());
    }

    /**
     * 订单项快照 → 契约行（字段逐字段投影，快照禁改）。
     *
     * @param item 订单项快照
     * @return 契约订单项行
     */
    private static SellerOrderItemView toItemView(OrderItem item) {
        return new SellerOrderItemView(
                item.getProductId(), item.getSkuId(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), item.getSubtotal(),
                item.getMainImageUrl(), item.getSpecSummary());
    }

    /**
     * 运单概要装配：引用为空（未发货）返回 null；引用悬挂（已发货但
     * 运单行缺失 = 数据异常）按 null 概要容忍呈现（展示驱动，不阻断
     * 详情读取）。
     *
     * @param waybillId 子单运单引用（可空）
     * @return 运单概要；未发货或悬挂为 null
     */
    private SellerWaybillView toWaybill(Long waybillId) {
        if (waybillId == null) {
            return null;
        }
        final Waybill waybill = waybillRepository.getByID(waybillId);
        if (waybill == null) {
            return null;
        }
        return new SellerWaybillView(waybill.getCompany(), waybill.getTrackingNo(),
                waybill.getStatus().name());
    }
}