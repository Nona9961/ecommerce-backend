package com.nona.application.support;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.service.InventoryReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存保留用例（跨端共用编排层薄壳）：订单驱动的库存保留生命周期四
 * 操作——下单预占 / 支付确认扣减 / 取消与超时回滚 / 退款回补。
 * <p>
 * 本类保留两样契约面（既有消费方/验收链引用不变）：
 * <ol>
 *     <li>原 {@code @Transactional} 事务边界——编排方法体已下沉库存域
 *         服务（{@link InventoryReservationService}），本类方法体仅委托
 *         （application → domain 单向依赖，红线内合法形态）；事务仍由
 *         本类边界承载，域服务不持事务注解；</li>
 *     <li>签名兼容面：同签名调用方（既有 InventoryReservation*AcTest
 *         真链断言、后续编排面）语义不变——幂等键/条件更新/流水/事件
 *         语义由下游域服务承载，行为等价。</li>
 * </ol>
 * 编排语义说明（下沉前为类内实现，现归 {@link InventoryReservationService}
 * 承载——见其 javadoc：校验订单上下文 → 幂等判定 → 定位加载聚合
 * （fail-closed）→ 仓储条件更新 → 聚合变更方法产流水 → 流水 append-only
 * 追加 → 事件统一触发点判定，同事务语义由本类事务边界并入）。
 *
 * @author nona9961
 */
@Service
public class InventoryReservationUseCase {

    /**
     * 库存保留领域服务（编排方法体下沉面）
     */
    private final InventoryReservationService inventoryReservationService;

    /**
     * 构造库存保留用例（薄壳）。
     *
     * @param inventoryReservationService 库存保留领域服务（必填）
     */
    public InventoryReservationUseCase(InventoryReservationService inventoryReservationService) {
        this.inventoryReservationService = inventoryReservationService;
    }

    /**
     * 预占（下单驱动）：可售减少、预占增加——并发防线为下游域服务转
     * 发仓储条件更新（available >= demand），恰好 M 份库存只放行 M 笔
     * 预占（防超卖）。
     *
     * @param orderId 订单 ID（必填；幂等键组成）
     * @param skuId   预占 SKU（必填）
     * @param demand  预占数量（必须为正）
     * @return 预占后的库存聚合（本请求视角三态与版本）
     */
    @Transactional
    public InventoryItem preoccupy(Long orderId, Long skuId, int demand) {
        return inventoryReservationService.preoccupy(orderId, skuId, demand);
    }

    /**
     * 确认扣减（支付成功驱动）：预占减少、已售增加——并发防线为下游
     * 域服务转发仓储条件更新（held >= quantity，防扣减超预占）。
     *
     * @param orderId  订单 ID（必填；幂等键组成）
     * @param skuId    扣减 SKU（必填）
     * @param quantity 扣减数量（必须为正）
     * @return 扣减后的库存聚合（本请求视角三态与版本）
     */
    @Transactional
    public InventoryItem confirmDeduct(Long orderId, Long skuId, int quantity) {
        return inventoryReservationService.confirmDeduct(orderId, skuId, quantity);
    }

    /**
     * 预占回滚（取消/超时释放驱动）：预占减少、可售增加——并发防线为
     * 下游域服务转发仓储条件更新（held >= quantity，防回滚超预占）。
     *
     * @param orderId  订单 ID（必填；幂等键组成）
     * @param skuId    回滚 SKU（必填）
     * @param quantity 回滚数量（必须为正）
     * @return 回滚后的库存聚合（本请求视角三态与版本）
     */
    @Transactional
    public InventoryItem rollback(Long orderId, Long skuId, int quantity) {
        return inventoryReservationService.rollback(orderId, skuId, quantity);
    }

    /**
     * 退款回补（未发货退款/发货超时关单驱动：已售减少、可售增加——
     * 货未出库退回可售；已发货/已完成不回补由退款编排层按子单状态
     * 判定保障，本契约只承载域能力）。幂等键 (order_id, sku_id, type)
     * 同三操作复用：同一订单同一 SKU 的 REFUND_RESTORE 只允许一次。
     *
     * @param orderId  订单 ID（必填；幂等键组成）
     * @param skuId    回补 SKU（必填）
     * @param quantity 回补数量（必须为正）
     * @return 回补后的库存聚合（本请求视角三态与版本）
     */
    @Transactional
    public InventoryItem restore(Long orderId, Long skuId, int quantity) {
        return inventoryReservationService.restore(orderId, skuId, quantity);
    }
}