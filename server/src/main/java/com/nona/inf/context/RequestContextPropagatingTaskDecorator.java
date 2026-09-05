package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;

/**
 * 将当前上下文三元组（tenantID / role / identity）及追踪基线传播到异步
 * worker 线程：经 {@link TenantContextAccessor} 的静态 {@link ScopedValue} 回退槽
 * 与 {@link TrackingContext} 作用域双槽嵌套绑定还原。
 * <p>
 * <strong>生命周期</strong>：
 * <ol>
 *   <li>{@link #decorate(Runnable)} 在提交线程经访问器捕获 {@link TenantContextAccessor.ContextSnapshot}
 *       （三元组 + {@code trackingBaseline}——仅当提交线程作用域已创建追踪器时
 *       {@code tracker.captureBaseline()} 深拷贝导出，不触发创建）</li>
 *   <li>worker 线程以<b>双槽嵌套绑定</b>执行任务：{@code TenantContextAccessor.withSnapshot(
 *       snapshot, () -> TrackingContext.withScope(task))}——外层绑定 SNAPSHOT 槽（三元组
 *       回退视角），内层绑定 TRACKING 槽（worker 独立 {@code TrackingScope}，首次
 *       {@code tracker()} 从基线重建）；作用域退出（含异常路径）两槽自动恢复 unbound，
 *       无需手动清理（JEP 506 语义）</li>
 * </ol>
 * <strong>注册</strong>：下游项目手动将本装饰器绑定到 {@code ThreadPoolTaskExecutor}：
 * <pre>{@code
 * executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
 * }</pre>
 * 不提供自动配置——每个异步执行器必须显式接入。
 *
 * @author nona9961
 */
@Slf4j
@ScaffoldGenerated
public class RequestContextPropagatingTaskDecorator implements TaskDecorator {

    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造装饰器：使用给定的访问器在提交线程捕获上下文快照。
     *
     * @param tenantContextAccessor 上下文访问器（预期为单例 Spring bean）
     */
    public RequestContextPropagatingTaskDecorator(TenantContextAccessor tenantContextAccessor) {
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 提交线程经访问器捕获当前上下文的
     * {@link TenantContextAccessor.ContextSnapshot}（三元组 + 追踪基线深拷贝），
     * worker 线程经 {@link TenantContextAccessor#withSnapshot} 与
     * {@link TrackingContext#withScope} 双槽嵌套绑定后执行任务。
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        final TenantContextAccessor.ContextSnapshot snapshot = tenantContextAccessor.captureSnapshot();
        log.debug("Captured context snapshot: tenantID={}, role={}, identity={}, trackingBaselinePresent={}",
                snapshot.tenantID(), snapshot.role(), snapshot.identity(), snapshot.trackingBaseline() != null);
        return () -> TenantContextAccessor.withSnapshot(snapshot,
                () -> TrackingContext.withScope(runnable));
    }
}
