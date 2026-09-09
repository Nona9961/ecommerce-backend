package com.nona.domain.inventory.repo;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 库存聚合根仓储接口（inventory_item 主表持久化契约，实现在基础设施层）。
 * <p>
 * inventory_item 表 tenant=shopId：读经租户过滤（fail-closed——跨店铺
 * SKU 库存按不存在呈现，归属不泄露）；写出由写门禁按请求上下文注入
 * tenant（商家请求 tenant=当前店铺）。sku_id 为业务唯一键（唯一约束兜底
 * 重复初始化冲突）。乐观锁 version 列随变更递增推进（读回值经聚合构造
 * 守卫非负，变更路径推进规则见 {@link InventoryItem}）。
 * <p>
 * 删除语义：库存行随 SKU 生命周期整体管理（本阶段无删除场景），
 * 基类 delete/deleteByID 不承载业务删除路径。
 *
 * @author nona9961
 */
public interface InventoryItemRepository extends BaseRepository<Long, InventoryItem> {

    /**
     * 按 SKU 业务键取库存聚合。
     *
     * @param skuId 归属 SKU ID
     * @return 库存聚合；不存在或跨店铺（租户过滤）返回 null
     */
    InventoryItem getBySkuId(Long skuId);

    /**
     * 条件更新预占（防超卖的持久化防线）：单语句原子执行
     * <code>available −= demand、held += demand、version += 1</code>，
     * WHERE 以 {@code available >= demand} 为业务量条件（基于 DB 当前值
     * 判定，行锁串行化下正好 M 份库存只放行 M 笔预占——超量请求命中 0 行）。
     * <p>
     * 形态约定：
     * <ul>
     *     <li>返回受影响行数（1=条件命中并推进、0=容量不足拒绝），
     *         delete/deleteByID 同款持久化语义；拒绝的业务语义（不足项）
     *         由编排层翻译为业务异常，本方法不抛异常（受影响行数判定）；</li>
     *     <li>租户条件（tenant_id=shopId）由实现从请求上下文注入——
     *         不接收租户参数（防跨店条件更新：调用方无法伪造他人店铺的
     *         租户条件），上下文缺失按 fail-closed 拒绝；</li>
     *     <li>version 列随更新逐笔 +1（SQL 侧算术推进），不参与条件判定
     *         （冲突检测辅助列，不承担防超卖职责）；</li>
     *     <li>本方法为条件更新接缝：不登记变更追踪、不经差异归集落库
     *         （更新语义由 WHERE 条件完整表达）。</li>
     * </ul>
     *
     * @param itemId 库存聚合根 ID
     * @param demand 预占数量（必须为正，由聚合前置守卫先行校验）
     * @return 受影响行数（1=成功；0=可售不足或行不存在或跨店铺）
     */
    int casPreoccupy(Long itemId, int demand);

    /**
     * 条件更新确认扣减（防扣减超预占）：单语句原子执行
     * <code>held −= quantity、sold += quantity、version += 1</code>，
     * WHERE 以 {@code held >= quantity} 为业务量条件。形态约定同
     * {@link #casPreoccupy(Long, int)}（返回受影响行数、租户条件注入、
     * version 不参与判定）。
     *
     * @param itemId   库存聚合根 ID
     * @param quantity 扣减数量（必须为正）
     * @return 受影响行数（1=成功；0=预占不足或行不存在或跨店铺）
     */
    int casConfirmDeduct(Long itemId, int quantity);

    /**
     * 条件更新预占回滚（防回滚超预占）：单语句原子执行
     * <code>held −= quantity、available += quantity、version += 1</code>，
     * WHERE 以 {@code held >= quantity} 为业务量条件。形态约定同
     * {@link #casPreoccupy(Long, int)}（返回受影响行数、租户条件注入、
     * version 不参与判定）。
     *
     * @param itemId   库存聚合根 ID
     * @param quantity 回滚数量（必须为正）
     * @return 受影响行数（1=成功；0=预占不足或行不存在或跨店铺）
     */
    int casRollback(Long itemId, int quantity);

    /**
     * 条件更新手工调整可售（防调整致负的持久化防线，与防超卖条件更新
     * 同源）：单语句原子执行 <code>available += delta、version += 1</code>，
     * WHERE 以 {@code available + delta >= 0} 为业务量条件（基于 DB 当前
     * 值判定——调整致负一律拒绝，不因加载快照陈旧而放行）。形态约定同
     * {@link #casPreoccupy(Long, int)}（返回受影响行数、租户条件注入、
     * version 不参与判定；delta 带符号，正=增可售、负=减可售）。
     *
     * @param itemId 库存聚合根 ID
     * @param delta  可售调整量（带符号；非零，由聚合前置守卫先行校验）
     * @return 受影响行数（1=成功；0=调整致负或行不存在或跨店铺）
     */
    int casAdjust(Long itemId, int delta);

    /**
     * 条件更新退款回补（防回补超已售的持久化防线，与防超卖条件更新
     * 同源）：单语句原子执行
     * <code>sold −= quantity、available += quantity、version += 1</code>，
     * WHERE 以 {@code sold >= quantity} 为业务量条件（基于 DB 当前值
     * 判定——回补不超已售，不因加载快照陈旧而放行）。形态约定同
     * {@link #casPreoccupy(Long, int)}（返回受影响行数、租户条件注入、
     * version 不参与判定）。
     *
     * @param itemId   库存聚合根 ID
     * @param quantity 回补数量（必须为正，由聚合前置守卫先行校验）
     * @return 受影响行数（1=成功；0=已售不足或行不存在或跨店铺）
     */
    int casRestore(Long itemId, int quantity);

    /**
     * 店铺库存分页列表（WU-47 商家库存列表页查询面「服务端分页」，
     * WU-55 冻结；全量分页无业务键过滤——当前店铺全集，租户过滤
     * fail-closed：跨店铺请求按空呈现，归属不泄露）。
     * <p>
     * 排序冻结：主键 ID 升序（创建加载序，稳定分页——与仓储从表装载
     * 的按加载序 ID 升序同纪律）。
     * <p>
     * <b>参数守卫（fail-closed）</b>：{@code offset < 0} 或
     * {@code limit <= 0} 抛 {@link IllegalArgumentException}；offset
     * 超出全集返回空列表（fail-safe，不抛异常）。
     * <p>
     * 读取面纪律：分页行未登记变更追踪（只读呈现——InventoryLog
     * 分页先例同款）；如需变更保存须经 {@link #getByID} 重新装载
     * 建立快照基线。
     *
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数（正数）
     * @return 库存行列表（主键升序）；无命中为空列表
     */
    List<InventoryItem> listPaged(int offset, int limit);

    /**
     * 店铺库存总数（分页 total 用，语义与 {@link #listPaged} 一致——
     * 当前租户店铺全集）。
     *
     * @return 命中库存行数
     */
    long count();
}