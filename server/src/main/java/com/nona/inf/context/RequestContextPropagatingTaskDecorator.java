package com.nona.inf.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 将 {@link ThreadContext}（tenantID / role / identity）传播到异步 worker 线程：
 * 经 {@link TenantContextAccessor} 的静态 {@link ScopedValue} 回退槽。
 * <p>
 * <strong>生命周期</strong>：
 * <ol>
 *   <li>{@link #decorate(Runnable)} 在提交线程经访问器捕获 {@link TenantContextAccessor.ContextSnapshot}</li>
 *   <li>快照在 worker 线程以结构化作用域绑定执行任务——作用域退出（含异常路径）自动恢复
 *       unbound，无需手动清理（JEP 506 语义）</li>
 * </ol>
 * <strong>注册</strong>：下游项目手动将本装饰器绑定到 {@code ThreadPoolTaskExecutor}：
 * <pre>{@code
 * executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
 * }</pre>
 * 不提供自动配置——每个异步执行器必须显式接入。
 *
 * @author nona
 */
@Slf4j
@ScaffoldGenerated
public class RequestContextPropagatingTaskDecorator implements TaskDecorator {

    private final TenantContextAccessor tenantContextAccessor;

    /**
     * Constructs a new decorator that uses the given accessor to snapshot the submitting
     * thread's context.
     *
     * @param tenantContextAccessor the context accessor (expected to be a singleton Spring bean)
     */
    public RequestContextPropagatingTaskDecorator(TenantContextAccessor tenantContextAccessor) {
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 提交线程经访问器捕获当前 {@link ThreadContext} 的
     * {@link TenantContextAccessor.ContextSnapshot}，worker 线程经
     * {@link TenantContextAccessor#withSnapshot} 绑定执行。
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        final TenantContextAccessor.ContextSnapshot snapshot = tenantContextAccessor.captureSnapshot();
        log.debug("Captured context snapshot: tenantID={}, role={}, identity={}",
                snapshot.tenantID(), snapshot.role(), snapshot.identity());
        return () -> TenantContextAccessor.withSnapshot(snapshot, runnable);
    }
}
