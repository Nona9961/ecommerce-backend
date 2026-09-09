package com.nona.domain.order.repo;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.persistence.BaseRepository;

import java.util.Collection;
import java.util.List;

/**
 * 主订单仓储接口：买家主订单的持久化契约（master_order 主表，
 * 买家维度 global）。
 * <p>
 * 实现在基础设施层（继承 DifferRepository：主表快照追踪 + 变更集驱动
 * 落库——主表唯一行，无从表）。领域层只依赖本契约，不感知 JPA。聚合根
 * 主表以独立主键（masterOrderId）承载身份；order_no 唯一（业务单号）、
 * buyer_id 为业务关联列；子单 id 集合经 sub_order.master_order_id
 * 反查装载（引用 ID 协作，主单不加载子单对象）。
 * <p>
 * 查询契约扩展遵循「契约演进只增不改」：订单列表（B9.1）/详情
 * （B9.2）等买家/商家查询面随消费 WU 声明扩展，本阶段只冻结创建与
 * 按 ID 装载。
 * <p>
 * <b>买家列表分页面（WU-55 冻结）</b>：WU-44 约定的买家订单列表
 * 形状（GET /mall/orders?status=&amp;pageNum=&amp;pageSize=，7 态 tab）
 * ——状态 tab → 枚举值集合的映射收敛在消费编排层（本接口只承载多值
 * 条件过滤，不感知 tab 语义）。
 *
 * @author nona9961
 */
public interface MasterOrderRepository extends BaseRepository<Long, MasterOrder> {

    /**
     * 买家订单分页列表（B9.1 买家列表查询面，WU-44 约定形状）。
     * <p>
     * 定位：master_order 为 global 表（无租户隔离），按 buyer_id 业务
     * 关联列唯一定位买家订单全集；状态多值条件过滤（{@code status IN
     * (...) }）；排序冻结——创建时间倒序 + 主键倒序 tie-breaker（同事务
     * 批量落库的 create_time 相同，主键保证分页次序稳定）。
     * <p>
     * <b>状态过滤语义</b>：{@code statuses} 为 null 或空集合 = 不过滤
     * （全量——对应前端「全部」tab 不传 status 参数）；非空 = 多值
     * IN 过滤（映射收敛上层：如「待发货」tab → PAID 等枚举值集合）。
     * 无命中返回空列表（fail-safe，不抛异常）。
     * <p>
     * <b>参数守卫（fail-closed）</b>：{@code offset < 0} 或
     * {@code limit <= 0} 抛 {@link IllegalArgumentException}（调用方
     * 参数装配错误早暴露，禁止以「空页」语义静默吞掉非法分页）。
     * <p>
     * 读取面纪律：分页行未登记变更追踪（只读呈现——InventoryLog
     * 分页先例同款）；如需变更保存须经 {@link #getByID} 重新装载建立
     * 快照基线。
     *
     * @param buyerId  归属买家账号 ID（必填）
     * @param statuses 状态多值过滤集合；null/空 = 不过滤（全量）
     * @param offset   首条偏移量（从 0 开始）
     * @param limit    每页条数（正数）
     * @return 订单列表（创建时间倒序）；无命中为空列表
     */
    List<MasterOrder> listPagedByBuyer(Long buyerId,
                                       Collection<MasterOrderStatus> statuses,
                                       int offset, int limit);

    /**
     * 买家订单总数（分页 total 用，过滤语义与
     * {@link #listPagedByBuyer} 完全一致——同一 statuses 集合）。
     *
     * @param buyerId  归属买家账号 ID（必填）
     * @param statuses 状态多值过滤集合；null/空 = 不过滤（全量）
     * @return 命中订单数
     */
    long countByBuyer(Long buyerId, Collection<MasterOrderStatus> statuses);
}