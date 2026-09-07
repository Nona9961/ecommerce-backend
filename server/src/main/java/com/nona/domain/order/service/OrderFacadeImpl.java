package com.nona.domain.order.service;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.util.List;

/**
 * OrderFacade 实现（订单侧状态推进契约，本阶段接线 cancel + autoComplete +
 * onPaid；markShipped 为其他编排 WU 消费面，本类仅保留冻结签名，实现体
 * 未接线）。
 * <p>
 * 接线语义（按 OrderFacade 接口 javadoc + 聚合契约，绿阶段实现依据）：
 * <ul>
 *     <li><b>cancel</b>：按 masterOrderId 装载主单——不存在 →
 *         {@code order.master_not_found}（404，编排层归属校验先行，此处
 *         为契约防御）；按 master_order_id 装载子单集合——为空 →
 *         {@code order.sub_not_found}（防御装配错误，下单保证至少一子单）；
 *         逐子单 {@link SubOrder#cancel()}（仅待支付可取消，已支付/已发货
 *         非法迁移由聚合守卫拒绝 {@code order.sub_status_illegal}——已支付
 *         走退款流程 B8.6②、已发货不可取消 B8.6③）；全部推进成功后按
 *         子单状态投影刷新主单整体状态（全部 CANCELLED → 主单已取消，
 *         派生见 {@link MasterOrder#deriveStatus}），子单与主单同批保存。
 *         已取消订单再取消的幂等成功语义<b>不落本类</b>——由编排层短路
 *         （编排先查主单状态，CANCELLED 直接返回），本类保持「非法状态
 *         拒绝」的契约语义（供防御与独立消费方）；</li>
 *     <li><b>autoComplete</b>：按 subOrderId 装载子单——不存在 →
 *         {@code order.sub_not_found}（404，编排层已先短路，此处为契约
 *         防御）；按子单归属主单装载主单——不存在 →
 *         {@code order.master_not_found}（404，防御）；按 master_order_id
 *         装载子单集合——为空 → {@code order.sub_not_found}（防御装配错误）；
 *         目标子单 {@link SubOrder#markCompleted()}（仅已发货可完成，未发货
 *         直接完成/重复完成为非法迁移——聚合守卫 {@code order.sub_status_illegal}）；
 *         全部推进成功后按子单状态投影刷新主单整体状态（全部完成 → 主单
 *         已完成，多子单部分完成 → 部分发货中间态，派生见
 *         {@link MasterOrder#deriveStatus}），子单与主单同批保存。已完成
 *         子单再完成的幂等成功语义<b>不落本类</b>——由编排层短路
 *         （编排先查目标子单状态，COMPLETED 直接返回），本类保持「非法
 *         状态拒绝」的契约语义（供防御与独立消费方）；</li>
 *     <li><b>onPaid</b>：按 masterOrderId 装载主单——不存在 →
 *         {@code order.master_not_found}（404，编排层归属校验先行，此处
 *         为契约防御）；按 master_order_id 装载子单集合——为空 →
 *         {@code order.sub_not_found}（防御装配错误，下单保证至少一子单）；
 *         逐子单 {@link SubOrder#markPaid()}（仅待支付可标记支付成功，已
 *         支付/已取消等非法迁移由聚合守卫拒绝 {@code order.sub_status_illegal}——
 *         重复支付推进即命中，幂等短路由回调端口支付单守卫承载）；全部推
 *         进成功后按子单状态投影刷新主单整体状态（全部已支付 → 主单已
 *         支付，派生见 {@link MasterOrder#deriveStatus}），子单与主单同批
 *         保存。整单支付语义——主单下全部子单同事务推进（payment → order
 *         → inventory 回调编排的订单侧消费面）；</li>
 *     <li><b>markShipped</b>：签名冻结（WU-27），由发货编排 WU 接线，本
 *         类实现体未接线（UOE）。</li>
 * </ul>
 * 库存回滚编排（取消场景）不落本类——跨域动作按应用层用例承载（编排
 * 用例经 InventoryFacade 逐子单回滚，与预占对称）。
 * <p>
 * 装配说明（红阶段）：本类为普通类（不注册 Spring bean——依赖的
 * MasterOrder/SubOrder 仓储接口当前无 JPA 实现，注册即装配错误；绿阶段
 * 仓储实现落地后补注解并复验上下文，参照 PaymentPortImpl 同规则）。
 *
 * @author nona9961
 */
public class OrderFacadeImpl implements OrderFacade {

    /**
     * 主订单仓储（主单装载与状态派生落库）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 子订单仓储（子单集合装载与状态推进落库）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 构造订单门面实现骨架。
     *
     * @param masterOrderRepository 主订单仓储（必填）
     * @param subOrderRepository    子订单仓储（必填）
     */
    public OrderFacadeImpl(MasterOrderRepository masterOrderRepository,
                           SubOrderRepository subOrderRepository) {
        this.masterOrderRepository = masterOrderRepository;
        this.subOrderRepository = subOrderRepository;
    }

    /**
     * 整单支付成功推进（消费者：支付回调用例，同事务压链回调编排面）。
     * <p>
     * 编排语义（绿阶段实现依据，见类 javadoc）：装载主单（不存在 404）
     * → 装载子单集合（空 404 防御）→ 逐子单 markPaid（聚合守卫仅待支付
     * 可迁移，重复推进按非法迁移拒绝——幂等短路由回调端口支付单守卫承
     * 载，本方法保持防御面契约）→ 主单按子单投影派生（全部已支付 → 主
     * 单已支付）→ 子单与主单同批保存。
     *
     * @param masterOrderId 主订单 ID（主单下全部子单同事务推进已支付）
     */
    @Override
    public void onPaid(Long masterOrderId) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(masterOrderId);
        if (masterOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在", 404);
        }
        final List<SubOrder> subOrders =
                subOrderRepository.getByMasterOrderId(masterOrderId);
        if (subOrders == null || subOrders.isEmpty()) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "主订单下无子订单（装配异常）", 404);
        }
        for (final SubOrder subOrder : subOrders) {
            subOrder.markPaid();
        }
        final List<SubOrderStatus> projection = subOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder subOrder : subOrders) {
            subOrderRepository.save(subOrder);
        }
        masterOrderRepository.save(masterOrder);
    }

    /**
     * 待支付取消/支付超时关单（订单侧状态推进；跨域编排见取消用例）。
     * <p>
     * 编排语义（绿阶段实现依据，见类 javadoc）：装载主单（不存在 404）
     * → 装载子单集合（空 404 防御）→ 逐子单 cancel（聚合守卫拒非法迁移）
     * → 主单按子单投影派生（全部取消 → 已取消）→ 子单与主单同批保存。
     * 已取消再取消的幂等短路由编排层承载，本方法对已取消子单按非法迁移
     * 拒绝（防御面）。
     *
     * @param masterOrderId 主订单 ID（主单下全部子单同事务取消）
     * @param reason        取消原因（可空——超时自动取消无原因）
     */
    @Override
    public void cancel(Long masterOrderId, String reason) {
        final MasterOrder masterOrder = masterOrderRepository.getByID(masterOrderId);
        if (masterOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在", 404);
        }
        final List<SubOrder> subOrders =
                subOrderRepository.getByMasterOrderId(masterOrderId);
        if (subOrders == null || subOrders.isEmpty()) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "主订单下无子订单（装配异常）", 404);
        }
        for (final SubOrder subOrder : subOrders) {
            subOrder.cancel();
        }
        final List<SubOrderStatus> projection = subOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder subOrder : subOrders) {
            subOrderRepository.save(subOrder);
        }
        masterOrderRepository.save(masterOrder);
    }

    /**
     * 子单发货推进（签名冻结，消费者：商家发货用例；实现随发货编排 WU
     * 接线，本阶段未实现）。
     *
     * @param subOrderId 子订单 ID
     * @param waybillId  运单 ID（必填）
     */
    @Override
    public void markShipped(Long subOrderId, Long waybillId) {
        throw new UnsupportedOperationException("markShipped 实现随发货编排 WU 接线");
    }

    /**
     * 完成推进——买家确认收货（B9.3）与收货超时自动完成（B9.4③）共用
     * （订单侧状态推进；事件发布与幂等短路见确认收货用例）。
     * <p>
     * 编排语义（绿阶段实现依据，见类 javadoc）：按 subOrderId 装载子单
     * （不存在 404）→ 按归属主单装载主单（不存在 404）→ 装载子单集合
     * （空 404 防御）→ 目标子单 markCompleted（聚合守卫仅已发货可完成）
     * → 主单按子单投影派生（全部完成 → 已完成，部分完成 → 部分发货）
     * → 子单与主单同批保存。已完成子单重复完成的幂等短路由编排层承载，
     * 本方法对已完成子单按非法迁移拒绝（防御面）。
     *
     * @param subOrderId 子订单 ID（已发货 → 已完成 + 主单派生）
     */
    @Override
    public void autoComplete(Long subOrderId) {
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        final MasterOrder masterOrder =
                masterOrderRepository.getByID(subOrder.getMasterOrderId());
        if (masterOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(),
                    "主订单不存在", 404);
        }
        final List<SubOrder> subOrders =
                subOrderRepository.getByMasterOrderId(subOrder.getMasterOrderId());
        if (subOrders == null || subOrders.isEmpty()) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "主订单下无子订单（装配异常）", 404);
        }
        subOrder.markCompleted();
        final List<SubOrderStatus> projection = subOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder item : subOrders) {
            subOrderRepository.save(item);
        }
        masterOrderRepository.save(masterOrder);
    }
}