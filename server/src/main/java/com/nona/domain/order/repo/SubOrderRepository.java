package com.nona.domain.order.repo;

import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.persistence.BaseRepository;

import java.time.Instant;
import java.util.Collection;
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
 * <p>
 * <b>商家列表分页面（WU-55 冻结）</b>：WU-47 约定的商家订单列表
 * 形状（GET /seller/orders?status=&amp;pageNum=&amp;pageSize=，status
 * 逗号分隔多枚举）——SubOrderStatus 全量 8 枚举透传（本接口不做任何
 * 枚举值收敛/裁剪，「仅全部 tab 可见」等映射语义收敛在消费编排层）。
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

    /**
     * 店铺订单分页列表（WU-47 商家列表查询面形状，WU-55 冻结）。
     * <p>
     * 定位：店铺维度 = {@code shop_id = shopId} 显式业务条件 + 租户
     * 过滤（tenant_id=context）双层防线（fail-closed：任意一重不满足
     * 即无结果——跨店请求按不存在/空呈现，归属不泄露）；状态多值
     * 条件过滤（{@code status IN (...) }）；排序冻结——创建时间倒序 +
     * 主键倒序 tie-breaker（同 {@link #listPagedByShop(Long,
     * java.util.Collection, int, int)} 稳定分页）。
     * <p>
     * <b>状态过滤语义</b>：{@code statuses} 为 null 或空集合 = 不过滤
     * （全量——对应前端「全部」tab 不传 status 参数）；非空 = 多值
     * IN 过滤（SubOrderStatus 全量 8 枚举透传）。无命中返回空列表
     * （fail-safe）。
     * <p>
     * <b>参数守卫（fail-closed）</b>：{@code offset < 0} 或
     * {@code limit <= 0} 抛 {@link IllegalArgumentException}。
     * <p>
     * 读取面纪律：分页行未登记变更追踪（只读呈现）；如需变更保存
     * 须经 {@link #getByID} 重新装载建立快照基线。
     *
     * @param shopId   归属店铺 ID（必填；与租户过滤共同定位）
     * @param statuses 状态多值过滤集合；null/空 = 不过滤（全量）
     * @param offset   首条偏移量（从 0 开始）
     * @param limit    每页条数（正数）
     * @return 订单列表（创建时间倒序）；无命中为空列表
     */
    List<SubOrder> listPagedByShop(Long shopId,
                                   Collection<SubOrderStatus> statuses,
                                   int offset, int limit);

    /**
     * 店铺订单总数（分页 total 用，过滤语义与 {@link #listPagedByShop}
     * 完全一致）。
     *
     * @param shopId   归属店铺 ID（必填）
     * @param statuses 状态多值过滤集合；null/空 = 不过滤（全量）
     * @return 命中订单数
     */
    long countByShop(Long shopId, Collection<SubOrderStatus> statuses);
}