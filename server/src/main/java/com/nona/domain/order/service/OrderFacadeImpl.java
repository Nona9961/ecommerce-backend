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
import org.springframework.stereotype.Service;

/**
 * OrderFacade 实现（订单侧状态推进契约，七成员全部接线：cancel + onPaid
 * + markShipped + autoComplete + beginRefund + completeRefund + closeByTimeout；markShipped 为商家发货编排消费面——运单创建后子单发货
 * 推进 + 运单引用定型 + 主单派生，双参冻结签名）。
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
 *     <li><b>markShipped</b>：按 subOrderId 装载子单——不存在 →
 *         {@code order.sub_not_found}（404，编排层归属校验先行，此处为
 *         契约防御）；按子单归属主单装载主单——不存在 →
 *         {@code order.master_not_found}（404，防御）；按 master_order_id
 *         装载子单集合——为空 → {@code order.sub_not_found}（防御装配
 *         错误，下单保证至少一子单）；目标子单
 *         {@link SubOrder#markShipped(Long, Long)}（归属校验/运单必填/
 *         仅已支付可发货守卫内建——操作者店铺以子单归属店铺自证：用例
 *         层归属校验先行保证相等，门面签名双参冻结不携带操作者上下文）；
 *         全部推进成功后按子单状态投影刷新主单整体状态（全部已发货 →
 *         主单已发货，多子单部分发货 → 部分发货，派生见
 *         {@link MasterOrder#deriveStatus}），子单与主单同批保存。已发
 *         货子单再发货的幂等成功语义<b>不落本类</b>——由编排层短路
 *         （编排先查目标子单状态，SHIPPED 直接返回），本类保持「非法
 *         状态拒绝」的契约语义（供防御与独立消费方）；</li>
 * </ul>
 * 库存回滚编排（取消场景）不落本类——跨域动作按应用层用例承载（编排
 * 用例经 InventoryFacade 逐子单回滚，与预占对称）。
 * <p>
 * 装配说明：本类已注册 Spring bean（@Service）——依赖的 MasterOrder/
 * SubOrder 仓储接口自接线阶段（仓储实现落地）起有 JPA 实现，注册合法。
 * <p>
 * 保存语义（5 个按 subOrderId 定位的方法：markShipped/autoComplete/
 * beginRefund/completeRefund/closeByTimeout）：目标子单经 {@code getByID}
 * 装载为独立实例并原地推进状态，会话列表（{@code getByMasterOrderId}）
 * 为另一次装配（快照基线仅 getByID 路径登记）——保存段将目标被修改
 * 实例替换进列表后同批保存（保存被修改的实例，且主单投影按替换后列表
 * 派生，保证目标子单状态迁移落库与主单派生正确）。onPaid/cancel 按主单
 * 装载路径推进（逐子单同实例修改），无引用区分问题。
 *
 * @author nona9961
 */
@Service
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
     * 子单发货推进（签名冻结双参；消费者：商家发货用例——同事务创建运
     * 单后调用，运单引用定型入子单聚合）。
     * <p>
     * 编排语义：按 subOrderId 装载子单（不存在 404，编排层归属校验先行，
     * 此处为契约防御）→ 按归属主单装载主单（不存在 404，防御）→ 装载
     * 子单集合（空 404 防御装配错误）→ 目标子单
     * {@link SubOrder#markShipped(Long, Long)}（归属 403 / 运单必填 /
     * 仅已支付可发货三项守卫内建——操作者店铺以子单归属店铺自证：用例
     * 层归属校验已保证操作者店铺 = 子单归属店铺，不符早按不存在 404
     * 呈现，门面签名双参冻结不携带操作者上下文）→ 主单按子单投影派生
     * （全部已发货 → 主单已发货；多子单部分发货 → 部分发货）→ 子单与
     * 主单同批保存。已发货子单重复发货的幂等成功语义由编排层短路承载
     * （编排先查目标子单状态），本方法对非已支付子单按非法迁移拒绝
     * （防御面）。
     *
     * @param subOrderId 子订单 ID（已支付 → 已发货 + 运单引用定型）
     * @param waybillId  运单 ID（必填，物流域创建后引用）
     */
    @Override
    public void markShipped(Long subOrderId, Long waybillId) {
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
        // 操作者店铺以子单归属店铺自证：用例层归属校验先行保证二者相等
        // （不符早按不存在 404 呈现），门面签名双参冻结不携带操作者上下文
        subOrder.markShipped(subOrder.getShopId(), waybillId);
        final List<SubOrder> updatedSubOrders = subOrders.stream()
                .map(item -> item.getId().equals(subOrder.getId()) ? subOrder : item)
                .toList();
        final List<SubOrderStatus> projection = updatedSubOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder item : updatedSubOrders) {
            subOrderRepository.save(item);
        }
        masterOrderRepository.save(masterOrder);
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
        final List<SubOrder> updatedSubOrders = subOrders.stream()
                .map(item -> item.getId().equals(subOrder.getId()) ? subOrder : item)
                .toList();
        final List<SubOrderStatus> projection = updatedSubOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder item : updatedSubOrders) {
            subOrderRepository.save(item);
        }
        masterOrderRepository.save(masterOrder);
    }

    /**
     * 退款申请推进（订单侧状态推进；退款单创建/受理与库存回补见退款
     * 申请编排）。
     * <p>
     * 编排语义（绿阶段实现依据，见 OrderFacade 接口 javadoc）：按
     * subOrderId 装载子单（不存在 404，编排层归属校验先行，此处为
     * 契约防御）→ 按归属主单装载主单（不存在 404，防御）→ 装载子单
     * 集合（空 404 防御装配错误）→ 目标子单 {@code markRefunding}
     * （聚合守卫仅已支付/已发货/已完成可进入退款中，未支付/终态非法
     * 迁移拒绝 {@code order.sub_status_illegal}——B8.4① 内建）→ 主单
     * 按子单投影刷新整体状态（任一退款中 → 主单退款中，派生见
     * {@link com.nona.domain.order.entity.MasterOrder#deriveStatus}）
     * → 子单与主单同批保存。防重短路<b>不落本类</b>——由退款申请编排
     * 先行判定（退款单按子单查重，一子单一退款单），本类保持「非法
     * 状态拒绝」的契约语义（供防御与独立消费方）。
     *
     * @param subOrderId 子订单 ID（已支付/已发货/已完成 → 退款中 + 主单派生）
     */
    @Override
    public void beginRefund(Long subOrderId) {
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
        subOrder.markRefunding();
        final List<SubOrder> updatedSubOrders = subOrders.stream()
                .map(item -> item.getId().equals(subOrder.getId()) ? subOrder : item)
                .toList();
        final List<SubOrderStatus> projection = updatedSubOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder item : updatedSubOrders) {
            subOrderRepository.save(item);
        }
        masterOrderRepository.save(masterOrder);
    }

    /**
     * 退款成功推进（订单侧状态推进；资金侧迁移/回补见退款回调成功编排）。
     * <p>
     * 编排语义（绿阶段实现依据，见 OrderFacade 接口 javadoc）：按
     * subOrderId 装载子单（不存在 404）→ 子单状态分派——
     * {@code REFUNDING} → 按归属主单装载主单（不存在 404，防御）→
     * 装载子单集合 → 目标子单 {@code markRefunded}（仅退款中可迁移）→
     * 主单派生（全部已退款 → 主单已退款）→ 同批保存；{@code CLOSED}
     * → <b>幂等跳过</b>（发货超时关单退款路径：履约侧终态定格，资金侧
     * 由退款单 SUCCEEDED 承载，不迁移不落库）；其余状态 →
     * {@code order.sub_status_illegal}（防御拒绝数据异常）。
     *
     * @param subOrderId 子订单 ID（退款中 → 已退款 + 主单派生；已关闭幂等跳过）
     */
    @Override
    public void completeRefund(Long subOrderId) {
        final SubOrder subOrder = subOrderRepository.getByID(subOrderId);
        if (subOrder == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(),
                    "子订单不存在", 404);
        }
        // 发货超时关单退款路径：履约侧终态定格（CLOSED 而非 REFUNDED，领域模型
        // 明示），资金侧退款成功由退款单 SUCCEEDED 承载——幂等跳过，不迁移不落库
        if (subOrder.getStatus() == SubOrderStatus.CLOSED) {
            return;
        }
        if (subOrder.getStatus() != SubOrderStatus.REFUNDING) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅退款中子单可标记退款成功（未退款流程的子单属数据异常防御）");
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
        subOrder.markRefunded();
        final List<SubOrder> updatedSubOrders = subOrders.stream()
                .map(item -> item.getId().equals(subOrder.getId()) ? subOrder : item)
                .toList();
        final List<SubOrderStatus> projection = updatedSubOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder item : updatedSubOrders) {
            subOrderRepository.save(item);
        }
        masterOrderRepository.save(masterOrder);
    }

    /**
     * 发货超时关单推进（订单侧状态推进；退款单创建/受理与库存回补见
     * 发货超时编排——退款编排由调用方部署）。
     * <p>
     * 编排语义（绿阶段实现依据，见 OrderFacade 接口 javadoc）：按
     * subOrderId 装载子单（不存在 404）→ 按归属主单装载主单（不存在
     * 404，防御）→ 装载子单集合 → 目标子单 {@code closeByTimeout}
     * （聚合守卫仅已支付未发货可超时关单——支付超时走取消，重复关闭
     * 非法迁移拒绝 {@code order.sub_status_illegal}）→ 主单按子单投影
     * 刷新整体状态（全部已关闭 → 主单已关闭）→ 子单与主单同批保存。
     * 幂等短路<b>不落本类</b>——由编排层先行判定（子单状态预检，超时
     * 重扫不重复推进），本类保持「非法状态拒绝」的契约语义。
     *
     * @param subOrderId 子订单 ID（已支付 → 已关闭 + 主单派生）
     */
    @Override
    public void closeByTimeout(Long subOrderId) {
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
        subOrder.closeByTimeout();
        final List<SubOrder> updatedSubOrders = subOrders.stream()
                .map(item -> item.getId().equals(subOrder.getId()) ? subOrder : item)
                .toList();
        final List<SubOrderStatus> projection = updatedSubOrders.stream()
                .map(SubOrder::getStatus)
                .toList();
        masterOrder.deriveStatus(projection);
        for (final SubOrder item : updatedSubOrders) {
            subOrderRepository.save(item);
        }
        masterOrderRepository.save(masterOrder);
    }
}