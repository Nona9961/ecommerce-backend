package com.nona.inf.timeout;

import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 超时任务处理器：认领 → 触发 → 清除 deadline 三步编排（调度面消费入口）。
 * <p>
 * 事务边界（WU-49 修订）：三步同 <b>提权事务</b>（REQUIRES_NEW）——认领行锁
 * （业务表行更新）与业务写（fire 内目标用例的提权写段）同连接执行：若认领在
 * 外层普通事务而业务写在 REQUIRES_NEW 新连接，新连接对认领行锁的等待会触发
 * MySQL socketTimeout（8s）级联失败。fire 内目标用例（如 cancelByTimeout）的
 * 嵌套提权经 {@link TenantPrivilege#elevatedInTransaction} 嵌套去重同事务执行。
 * 回滚语义：任一步失败 → 内层事务整体回滚（含认领位）→ 异常透传 → 调度侧下轮
 * 重扫自然重试（不残留死认领行）。
 * <p>
 * 跟踪作用域：本类是调度面消费入口（{@code @Scheduled} 线程无入口组件绑定
 * TRACKING scope，而 fire 内目标用例的仓储操作（装载登记/变更集计算）要求
 * {\@code TrackingContext.withScope}——fail-closed）→ 方法内绑定系统上下文
 * （与测试手触 withScope 同形态；嵌套绑定安全，web/装饰器已绑定场景无副作用）。
 *
 * @author nona9961
 */
@Component
public class TimeoutTaskProcessor {

    /**
     * 处理器注册表（按超时类型路由）
     */
    private final TimeoutHandlerRegistry registry;

    /**
     * 提权工具（提权事务边界：无请求租户上下文写放行 + 嵌套去重）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（REQUIRES_NEW：提权作用域 = 事务边界）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造超时任务处理器。
     *
     * @param registry            超时处理器注册表
     * @param tenantPrivilege     提权工具
     * @param transactionTemplate 事务模板（REQUIRES_NEW）
     */
    public TimeoutTaskProcessor(TimeoutHandlerRegistry registry,
                                TenantPrivilege tenantPrivilege,
                                TransactionTemplate transactionTemplate) {
        this.registry = registry;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 认领 → 触发 → 清除 deadline 同提权事务执行；认领失败（并发抢占/
     * 状态不匹配）返回 false 无副作用。
     *
     * @param store 超时任务存储（认领/清除落地面）
     * @param task  到期任务（id + 业务目标引用）
     * @param <T>   任务目标类型
     * @return 是否处理成功（认领失败返回 false）
     */
    public <T> boolean processOne(TimeoutTaskStore<T> store, TimeoutTask<T> task) {
        final TimeoutHandler<T> handler = registry.get(store.type());
        final boolean[] handled = new boolean[1];
        TrackingContext.withScope(() -> {
            try {
                handled[0] = tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                    if (!store.claim(task)) {
                        return false;
                    }
                    handler.fire(task.target());
                    store.clearDeadline(task);
                    return true;
                });
            } catch (final RuntimeException e) {
                throw e;
            } catch (final Exception e) {
                throw new IllegalStateException("超时任务处理提权事务失败", e);
            }
        });
        return handled[0];
    }
}