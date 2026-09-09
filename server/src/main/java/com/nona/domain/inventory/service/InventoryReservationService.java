package com.nona.domain.inventory.service;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.domain.inventory.repo.InventoryLogRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 库存保留领域服务（库存域内编排层）：订单驱动的库存保留生命周期四
 * 操作——下单预占 / 支付确认扣减 / 取消与超时回滚 / 退款回补（同一
 * 订单同一 SKU 的同一类型变动只允许一次：幂等键 (order_id, sku_id,
 * type) 拒绝重复请求）。
 * <p>
 * 服务承载库存域内的操作编排定式（跨聚合/仓储的协作形态，非事务边
 * 界——本类无事务注解，事务由应用层调用方承载，REQUIRED 语义并入）：
 * <ol>
 *     <li>校验订单上下文（订单 ID 必填、变动数量为正——幂等判定与
 *         条件更新的前置形状校验）；</li>
 *     <li>幂等判定（预查询快速拒绝 + 流水表唯一约束兜底并发窗口，
 *         重复请求以 409 冲突拒绝）；</li>
 *     <li>按 SKU 定位库存行（租户过滤 fail-closed：不存在或跨店铺按
 *         不存在拒绝，归属不泄露），再经 getByID 加载聚合建立快照
 *         基线（接缝移交：条件更新前必须经主键加载保持快照一致）；</li>
 *     <li>仓储条件更新（并发防线：SQL 算术相对更新 + 业务量条件，
 *         受影响行数 0 即容量不足拒绝——翻译为业务异常并明确不足项）；</li>
 *     <li>聚合方法做领域前置守卫并内嵌构造流水（before/after 三态
 *         快照与 delta 在聚合内组装，基于加载快照的请求视角口径）；</li>
 *     <li>流水 append-only 追加——与条件更新同事务落库（任一失败整体
 *         回滚：不可追踪的库存变更不可能）；</li>
 *     <li>事件统一触发点判定（售罄/恢复）——变更后调用，与流水同事务
 *         （发布动作不落库，事务提交后投递）。</li>
 * </ol>
 * 事务边界归属：本类为领域层服务（不持 @Transactional），「与流水同
 * 事务」「整体回滚」语义由应用层调用方（商家/支付/订单用例的
 * @Transactional 边界，或 InventoryReservationUseCase 薄壳的事务边界）
 * 承载——调用方经 REQUIRED 语义并入本服务的编排步骤。
 *
 * @author nona9961
 */
@Component
public class InventoryReservationService {

    /**
     * 库存聚合根仓储（定位、快照加载与条件更新）
     */
    private final InventoryItemRepository inventoryItemRepository;

    /**
     * 库存流水仓储（幂等判定与流水追加）
     */
    private final InventoryLogRepository inventoryLogRepository;

    /**
     * 售罄/恢复事件统一触发点（订单驱动操作路径接入）
     */
    private final InventoryEventRouter inventoryEventRouter;

    /**
     * 构造库存保留领域服务。
     *
     * @param inventoryItemRepository 库存聚合根仓储
     * @param inventoryLogRepository  库存流水仓储
     * @param inventoryEventRouter    事件统一触发点
     */
    public InventoryReservationService(InventoryItemRepository inventoryItemRepository,
                                       InventoryLogRepository inventoryLogRepository,
                                       InventoryEventRouter inventoryEventRouter) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.inventoryLogRepository = inventoryLogRepository;
        this.inventoryEventRouter = inventoryEventRouter;
    }

    /**
     * 预占（下单驱动）：可售减少、预占增加——并发防线为仓储条件更新
     * （available >= demand），恰好 M 份库存只放行 M 笔预占（防超卖）。
     *
     * @param orderId 订单 ID（必填；幂等键组成）
     * @param skuId   预占 SKU（必填）
     * @param demand  预占数量（必须为正）
     * @return 预占后的库存聚合（本请求视角三态与版本）
     */
    public InventoryItem preoccupy(Long orderId, Long skuId, int demand) {
        requireOrderContext(orderId, demand, "预占数量必须为正");
        rejectDuplicate(orderId, skuId, InventoryLogType.PREOCCUPY);
        final InventoryItem item = loadItem(skuId);
        final int affected = inventoryItemRepository.casPreoccupy(item.getId(), demand);
        return commitChange(item, affected, "可售库存不足，无法预占",
                () -> item.preoccupy(orderId, demand));
    }

    /**
     * 确认扣减（支付成功驱动）：预占减少、已售增加——并发防线为仓储
     * 条件更新（held >= quantity，防扣减超预占）。
     *
     * @param orderId  订单 ID（必填；幂等键组成）
     * @param skuId    扣减 SKU（必填）
     * @param quantity 扣减数量（必须为正）
     * @return 扣减后的库存聚合（本请求视角三态与版本）
     */
    public InventoryItem confirmDeduct(Long orderId, Long skuId, int quantity) {
        requireOrderContext(orderId, quantity, "扣减数量必须为正");
        rejectDuplicate(orderId, skuId, InventoryLogType.CONFIRM);
        final InventoryItem item = loadItem(skuId);
        final int affected = inventoryItemRepository.casConfirmDeduct(item.getId(), quantity);
        return commitChange(item, affected, "预占库存不足，无法确认扣减",
                () -> item.confirmDeduct(orderId, quantity));
    }

    /**
     * 预占回滚（取消/超时释放驱动）：预占减少、可售增加——并发防线为
     * 仓储条件更新（held >= quantity，防回滚超预占）。
     *
     * @param orderId  订单 ID（必填；幂等键组成）
     * @param skuId    回滚 SKU（必填）
     * @param quantity 回滚数量（必须为正）
     * @return 回滚后的库存聚合（本请求视角三态与版本）
     */
    public InventoryItem rollback(Long orderId, Long skuId, int quantity) {
        requireOrderContext(orderId, quantity, "回滚数量必须为正");
        rejectDuplicate(orderId, skuId, InventoryLogType.ROLLBACK);
        final InventoryItem item = loadItem(skuId);
        final int affected = inventoryItemRepository.casRollback(item.getId(), quantity);
        return commitChange(item, affected, "预占库存不足，无法回滚",
                () -> item.rollback(orderId, quantity));
    }

    /**
     * 退款回补（未发货退款/发货超时关单驱动：已售减少、可售增加——
     * 货未出库退回可售；已发货/已完成不回补由退款编排层按子单状态
     * 判定保障，本服务只承载域能力）。并发防线为仓储条件更新
     * （sold >= quantity，防回补超已售——安全保证不回补多于已售）。
     * 幂等键 (order_id, sku_id, type) 同三操作复用：同一订单同一 SKU 的
     * REFUND_RESTORE 只允许一次（重复退款回调/重复请求不重复回补，
     * 预查询快速拒绝 + 流水表唯一约束兜底并发窗口）。编排面同三操作
     * 定式：校验订单上下文 → 幂等判定 → 定位加载聚合（租户过滤
     * fail-closed）→ 仓储条件更新（受影响行数 0 即已售不足拒绝）→
     * 聚合方法做领域前置守卫并内嵌构造 REFUND_RESTORE 流水 → 流水
     * append-only 追加 → 事件统一触发点判定（售罄/恢复——回补路径
     * 接入统一触发点，售罄态 SKU 经回补恢复可售发布恢复事件，一期
     * 日志消费）。
     *
     * @param orderId  订单 ID（必填；幂等键组成）
     * @param skuId    回补 SKU（必填）
     * @param quantity 回补数量（必须为正）
     * @return 回补后的库存聚合（本请求视角三态与版本）
     */
    public InventoryItem restore(Long orderId, Long skuId, int quantity) {
        requireOrderContext(orderId, quantity, "回补数量必须为正");
        rejectDuplicate(orderId, skuId, InventoryLogType.REFUND_RESTORE);
        final InventoryItem item = loadItem(skuId);
        final int affected = inventoryItemRepository.casRestore(item.getId(), quantity);
        return commitChange(item, affected, "已售数量不足，无法回补",
                () -> item.restoreSold(orderId, quantity));
    }

    /**
     * 变更收尾定式（订单驱动四操作共用的收尾形态）：条件更新命中判断
     * + 流水追加 + 事件判定。
     * <p>
     * 受影响行数 0 即容量/数量不足拒绝（翻译为业务异常，明确失败语义）——
     * 此时聚合变更方法不执行（流水构造不产），库存与流水均不动；命中时
     * 由聚合变更方法构造流水（before/after 三态快照与 delta 在聚合内组装），
     * 流水 append-only 追加与事件统一触发点判定（售罄/恢复）同事务落库
     * （事务边界由应用层调用方承载）。
     *
     * @param item           已加载的库存聚合（快照基线）
     * @param affected       仓储条件更新受影响行数（1=命中；0=拒绝）
     * @param failureMessage 受影响行数 0 时的拒绝消息
     * @param logFactory     聚合变更方法（流水构造；仅在条件更新命中后执行）
     * @return 变更后的库存聚合（本请求视角三态与版本）
     */
    private InventoryItem commitChange(InventoryItem item, int affected, String failureMessage,
                                       Supplier<InventoryLog> logFactory) {
        if (affected == 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code(), failureMessage);
        }
        final InventoryLog log = logFactory.get();
        inventoryLogRepository.append(log);
        inventoryEventRouter.publishIfNeeded(log);
        return item;
    }

    /**
     * 校验订单上下文（幂等判定与条件更新的前置形状校验）：订单 ID 必填
     * （幂等键组成）、变动数量必须为正；形状非法直接拒绝，不进入查询
     * 与更新路径。
     *
     * @param orderId  订单 ID
     * @param quantity 变动数量
     * @param invalidMessage 数量非正时的拒绝消息
     */
    private void requireOrderContext(Long orderId, int quantity, String invalidMessage) {
        if (orderId == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_INVALID.code(),
                    "订单驱动库存操作必须携带订单 ID");
        }
        if (quantity <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_QUANTITY_INVALID.code(), invalidMessage);
        }
    }

    /**
     * 幂等预查：同幂等键 (order_id, sku_id, type) 已有流水行 → 重复变更
     * 请求快速拒绝（409 冲突；并发窗口由流水表唯一约束兜底，兜底路径
     * 的异常在流水仓储转换为同一业务异常）。
     *
     * @param orderId 订单 ID
     * @param skuId   SKU ID
     * @param type    流水类型
     */
    private void rejectDuplicate(Long orderId, Long skuId, InventoryLogType type) {
        if (inventoryLogRepository.existsByIdempotencyKey(orderId, skuId, type)) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_LOG_DUPLICATE.code(),
                    "重复变更请求：同订单同 SKU 同类型的库存变动已存在");
        }
    }

    /**
     * 定位并加载库存聚合：先按 SKU 业务键定位（租户过滤 fail-closed——
     * 不存在或跨店铺按不存在拒绝），再经主键加载聚合建立快照基线
     * （接缝移交：变迁前的快照一致性与条件更新的行定位共同收敛于主键
     * 加载路径）。
     *
     * @param skuId 归属 SKU ID
     * @return 库存聚合（快照基线就位）
     */
    private InventoryItem loadItem(Long skuId) {
        final InventoryItem located = inventoryItemRepository.getBySkuId(skuId);
        if (located == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                    "SKU 库存不存在，库存操作被拒绝");
        }
        final InventoryItem item = inventoryItemRepository.getByID(located.getId());
        if (item == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                    "SKU 库存不存在，库存操作被拒绝");
        }
        return item;
    }
}