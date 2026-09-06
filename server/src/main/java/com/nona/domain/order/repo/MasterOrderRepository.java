package com.nona.domain.order.repo;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.persistence.BaseRepository;

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
 *
 * @author nona9961
 */
public interface MasterOrderRepository extends BaseRepository<Long, MasterOrder> {
}