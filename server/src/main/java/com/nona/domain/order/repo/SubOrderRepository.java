package com.nona.domain.order.repo;

import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.persistence.BaseRepository;

import java.time.Instant;
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

    /**
     * 履约超时扫描面（发货超时 PAID/收货超时 SHIPPED 共用）：返回状态
     * 处于预期态且截止时间已到期（timeout_at &lt;= now，含等号边界）的
     * 候选，按截止时间升序至多返回 limit 条（走 (status, timeout_at)
     * 复合索引）。
     * <p>
     * 条件语义（接线 WU 无漂移落地依据）——{@code status = 预期态 AND
     * timeout_at <= now}；不含 claimed 过滤（引擎事务语义保证无死认领
     * 行，见引擎契约）；调用方（履约超时数据端口）传入预期态与扫描
     * 时刻，本接口不写死任何状态值。
     *
     * @param status 预期态（发货超时 = PAID；收货超时 = SHIPPED，由调用方传入）
     * @param now    扫描时刻（到期判定边界，含等号）
     * @param limit  单轮扫描上限（引擎 SCAN_LIMIT 语义透传）
     * @return 到期候选；无到期返回空列表
     */
    List<SubOrder> findDueByStatusAndTimeoutAtBefore(SubOrderStatus status,
                                                     Instant now, int limit);

    /**
     * 乐观锁认领（履约超时数据端口 claim 的条件 UPDATE 落地面）：等价于
     * {@code UPDATE ... SET claimed = 1 WHERE id = ? AND claimed = 0
     * AND status = expectedStatus}。
     * <p>
     * 认领时刻复查状态条件：候选可能在扫描后被并发路径迁移（买家主动
     * 退款/确认收货已同步清除截止时间、或他方调度器已抢先认领），条件
     * 不满足即认领失败。影响行数 1 → true，0 → false。
     *
     * @param id             候选主键（sub_order 主键）
     * @param expectedStatus 认领复查的预期态（发货超时 = PAID；收货超时 = SHIPPED）
     * @return true = 认领成功；false = 已被认领或状态已迁移
     */
    boolean claimTimeout(Long id, SubOrderStatus expectedStatus);

    /**
     * 处理成功后清除截止时间（履约超时数据端口 clearDeadline 的落地面）：
     * 等价于 {@code UPDATE ... SET timeout_at = NULL, timeout_type = NULL,
     * claimed = 0 WHERE id = ?}——该行从此不再被扫描命中；成功路径同时
     * 清除认领位，不残留处理痕迹（无条件幂等：目标不存在/已清除均为
     * 无操作成功）。
     *
     * @param id 候选主键（sub_order 主键）
     */
    void clearTimeoutDeadline(Long id);
}