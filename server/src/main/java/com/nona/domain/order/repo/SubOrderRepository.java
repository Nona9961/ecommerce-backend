package com.nona.domain.order.repo;

import com.nona.domain.order.entity.SubOrder;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 子订单仓储接口：店铺子订单的持久化契约（sub_order 主表 + order_item
 * 从表，tenant=shopId）。
 * <p>
 * 实现在基础设施层（继承 DifferRepository：主表快照追踪 + 变更集驱动
 * 落库——从表条目新增插行/字段变更整行更新；归属店铺（tenant）随租户
 * 过滤器 fail-closed 隔离：商家按 ID 装载只命中本店子单，跨店按不存在
 * 呈现）。领域层只依赖本契约，不感知 JPA。聚合根主表以独立主键
 * （subOrderId）承载身份；master_order_id 为业务关联列（非唯一，主单
 * 下 N 子单）；子单 id 集合在主单侧按此列反查（引用 ID 协作）。
 * <p>
 * 查询契约扩展遵循「契约演进只增不改」：列表/分页/店铺维度查询随消费
 * WU（订单列表/详情查询面）声明，本阶段只冻结创建与主单维度装载。
 *
 * @author nona9961
 */
public interface SubOrderRepository extends BaseRepository<Long, SubOrder> {

    /**
     * 按归属主订单加载子单集合（支付推进/状态派生/买家订单详情编排
     * 用：主单下全部子单）。
     *
     * @param masterOrderId 主订单 ID
     * @return 子单列表（保持创建序）；无子单返回空列表——调用方经主单
     * 引用集合保证非空
     */
    List<SubOrder> getByMasterOrderId(Long masterOrderId);
}