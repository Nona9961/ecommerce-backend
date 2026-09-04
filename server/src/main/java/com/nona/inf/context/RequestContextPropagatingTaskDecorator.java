package com.nona.inf.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 将 {@link ThreadContext}（tenantID / role / identity）传播到异步 worker 线程：
 * 经 {@link TenantContextAccessor} 的静态 {@link ScopedValue} 回退槽；同时桥接
 * 发布线程的请求上下文（{@link RequestContextHolder}），使任务线程内访问
 * {@code @RequestScope} 的 {@link ThreadContext} 代理（DifferRepository 变更追踪
 * 链路）可解析。
 * <p>
 * <strong>生命周期</strong>：
 * <ol>
 *   <li>{@link #decorate(Runnable)} 在提交线程经访问器捕获 {@link TenantContextAccessor.ContextSnapshot}，
 *       并 null-safe 捕获当前 {@link RequestAttributes}（非请求线程发布为 null）</li>
 *   <li>worker 线程先绑定请求上下文（捕获非 null 时），再以结构化作用域绑定快照执行任务——
 *       作用域退出（含异常路径）自动恢复 unbound，无需手动清理（JEP 506 语义）</li>
 *   <li>{@link RequestContextHolder} 在任务结束（finally）复位，防止线程复用泄漏</li>
 * </ol>
 * <strong>注册</strong>：下游项目手动将本装饰器绑定到 {@code ThreadPoolTaskExecutor}：
 * <pre>{@code
 * executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
 * }</pre>
 * 不提供自动配置——每个异步执行器必须显式接入。
 * <p>
 * <strong>ecommerce 侧定制点（相对脚手架副本）</strong>：新增请求上下文桥接——事件消费
 * 编排（如售罄自动下架）在异步任务线程经 DifferRepository（@RequestScope ThreadContext
 * 代理）读/保存，无请求上下文时代理解析抛 IllegalStateException；桥接后任务线程复用发布
 * 线程的请求上下文（含其 ThreadContext 实例——同一请求至多一个业务任务，tracker 请求级
 * 单例串扰窗口≈0，见实现报告 residual risk 登记）。发布线程为非请求线程（捕获 null）
 * 时跳过桥接，仅 ScopedValue 三元组照常传播。
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
     * {@link TenantContextAccessor.ContextSnapshot} 与请求上下文（null-safe）；
     * worker 线程绑定请求上下文后经 {@link TenantContextAccessor#withSnapshot}
     * 执行任务，任务结束复位请求上下文。
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        final TenantContextAccessor.ContextSnapshot snapshot = tenantContextAccessor.captureSnapshot();
        final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        log.debug("Captured context snapshot: tenantID={}, role={}, identity={}, requestAttributes={}",
                snapshot.tenantID(), snapshot.role(), snapshot.identity(), requestAttributes != null);
        return () -> {
            if (requestAttributes != null) {
                RequestContextHolder.setRequestAttributes(requestAttributes);
            }
            try {
                TenantContextAccessor.withSnapshot(snapshot, runnable);
            } finally {
                if (requestAttributes != null) {
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        };
    }
}