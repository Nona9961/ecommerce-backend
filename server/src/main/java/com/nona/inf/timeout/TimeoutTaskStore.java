package com.nona.inf.timeout;

import java.time.Instant;
import java.util.List;

/**
 * 超时任务数据端口：候选扫描 / 乐观锁认领 / 截止时间清除。
 * <p>
 * 引擎是纯调度器，不接触任何业务表；截止时间以冗余列承载在业务侧
 * 表上（timeout_at / timeout_type / claimed，复合索引 (status, timeout_at)）。
 * 本端口按超时类型实现，内部持有该类型的「预期状态」知识——扫描与
 * 认领的状态条件即对应场景的业务状态（如待支付/待发货/待收货），
 * 对引擎不可见。
 *
 * @param <T> 业务对象引用类型
 */
public interface TimeoutTaskStore<T> {

    /**
     * 本端口服务的超时类型（与处理器的类型注册一一对应）。
     *
     * @return 超时类型
     */
    TimeoutType type();

    /**
     * 扫描到期候选：status = 预期态 且 timeout_at &lt;= now，按截止时间
     * 升序至多返回 limit 条（走 (status, timeout_at) 复合索引）。
     * <p>
     * 注意：扫描条件不含 claimed 过滤——已认领未清除的行只存在于
     * 「处理中」暂态：成功即清除（退出候选）、失败即随事务回滚
     * （认领位归零、可再认领）。不存在滞留的永久认领行是引擎
     * 事务语义的结果，而非扫描过滤的结果。
     *
     * @param now   当前时刻（引擎注入，保证可测试性）
     * @param limit 单轮扫描上限（防止单轮堆积）
     * @return 到期候选；无到期返回空表
     */
    List<TimeoutTask<T>> findDue(Instant now, int limit);

    /**
     * 乐观锁认领：等价于条件更新
     * {@code UPDATE ... SET claimed = 1 WHERE id = ? AND claimed = 0 AND status = 预期态}。
     * <p>
     * 认领时刻复查状态条件：候选可能在扫描后被并发路径迁移（如买家
     * 主动支付/取消已同步清除截止时间、或他方调度器已抢先认领），
     * 条件不满足即认领失败。影响行数 1 → true，0 → false。
     *
     * @param task 候选（以 id 定位）
     * @return true = 认领成功（本轮持有处理权）；false = 已被认领或状态已迁移
     */
    boolean claim(TimeoutTask<T> task);

    /**
     * 处理成功后清除截止时间（闭环）：等价于
     * {@code UPDATE ... SET timeout_at = NULL, timeout_type = NULL, claimed = 0 WHERE id = ?}，
     * 该行从此不再被扫描命中；成功路径同时清除认领位，不残留处理痕迹。
     *
     * @param task 候选（以 id 定位）
     */
    void clearDeadline(TimeoutTask<T> task);
}