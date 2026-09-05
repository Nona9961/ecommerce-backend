package com.nona.domain.inventory.ports;

import java.util.List;

/**
 * 库存门面（inventory 跨上下文契约，签名随本阶段冻结、消费方为订单/
 * 商品上游编排）：
 * <ul>
 *     <li>{@link #queryAvailable}——多 SKU 可售量查询（可售 = available
 *         列值，缺行按 0 呈现、跨店铺 fail-closed 不可见）；</li>
 *     <li>{@link #preoccupy} / {@link #confirmDeduct} / {@link #rollback}——
 *         订单驱动三动作（下单预占 / 支付确认扣减 / 取消与超时回滚），
 *         签名随冻结声明、门面动作面未接线（防超卖唯一机制为数据库条件
 *         更新；域内编排由应用层用例承载）；</li>
 *     <li>{@link #adjust}——商家手工调整可售（仅可售变动，调整后 ≥ 0），
 *         签名随冻结声明、门面动作面未接线（域内编排由商家端用例承载）；</li>
 *     <li>{@link #restore}——退款回补（已售回补可售），签名随冻结声明、
 *         门面动作面未接线（域内编排由应用层回补用例承载）。</li>
 * </ul>
 * 并发一致性：跨域编排（下单/支付/取消用例）在应用层事务内调用本门面与
 * 订单聚合推进，任一失败整体回滚。租户语义：域内读写
 * （tenant=shopId，fail-closed）；跨店铺下单预占的提权编排未落地
 * （当前无跨店铺提权路径）。
 *
 * @author nona9961
 */
public interface InventoryFacade {

    /**
     * 多 SKU 可售量查询（下单前置读/详情展示共用）。
     *
     * @param skuIds 请求的 SKU ID 集合（去重由实现保证）
     * @return 各 SKU 可售量列表——请求 SKU 全量返回（zip 语义）；未初始
     * 化或跨店铺 SKU 按可售 0 呈现（不泄露归属，买家视角不可售）
     */
    List<InventoryAvailable> queryAvailable(List<Long> skuIds);

    /**
     * 下单预占（订单驱动，签名冻结；门面动作面未接线，域内编排由应用
     * 层用例承载）。
     *
     * @param orderId 订单 ID
     * @param items   预占明细（SKU + 数量）
     */
    void preoccupy(Long orderId, List<StockChangeItem> items);

    /**
     * 支付成功确认扣减（订单驱动，签名冻结；门面动作面未接线，域内编
     * 排由应用层用例承载）。
     *
     * @param orderId 订单 ID
     * @param items   扣减明细（SKU + 数量）
     */
    void confirmDeduct(Long orderId, List<StockChangeItem> items);

    /**
     * 预占回滚（取消/超时释放，订单驱动，签名冻结；门面动作面未接线，
     * 域内编排由应用层用例承载）。
     *
     * @param orderId 订单 ID
     * @param items   回滚明细（SKU + 数量）
     */
    void rollback(Long orderId, List<StockChangeItem> items);

    /**
     * 商家手工调整可售（签名冻结；门面动作面未接线，域内编排由商家端
     * 用例承载）。
     *
     * @param skuId 目标 SKU ID
     * @param delta 可售调整量（带符号；调整后可售 ≥ 0）
     */
    void adjust(Long skuId, int delta);

    /**
     * 退款回补（未发货退款/发货超时关单驱动：已售回补可售——订单驱动，
     * 签名冻结、门面动作面未接线，域内编排由应用层回补用例承载；
     * 已发货/已完成不回补的语义由编排层按子单状态判定保障——本契约只
     * 承载域能力）。
     * <p>
     * 幂等键 (order_id, sku_id, type) 同预占/确认/回滚复用：同一订单
     * 同一 SKU 的 REFUND_RESTORE 只允许一次（重复退款回调不重复回补）；
     * 批量原子性由编排层同事务边界保障，本契约逐个 SKU 独立
     * 幂等、独立判定。
     *
     * @param orderId 订单 ID（必填；幂等键组成——退款操作单元为子订单）
     * @param items   回补明细（SKU + 数量——按子单退款持有 SKU 集合
     *                显式传入；多子单退分数次调用）
     */
    void restore(Long orderId, List<StockChangeItem> items);
}