package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.context.TenantPrivilege;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 为 {@link CrossTenant} 提供基于 Spring AOP 的读放行拦截：
 * 在方法/类作用域内建立读放行状态（读路径关闭租户过滤），退出后自动恢复。
 * <p>
 * 实现走 {@link TenantPrivilege#withReadBypass(Runnable)}（独立 ScopedValue 读放行状态）——
 * 不写 ThreadContext（与旧 ThreadContext 标记实现的区别）、不激活写提权（写门禁仍只认
 * {@link TenantPrivilege#isActive()}）。时序：{@link Ordered#HIGHEST_PRECEDENCE} 保证先于
 * 事务拦截器设置状态，session 打开时 resolver 自查能读到正确模式（提权/读放行须罩住事务边界的时序约定）。
 *
 * @author nona9961
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@ScaffoldGenerated
public class CrossTenantAspect {

    private final TenantPrivilege tenantPrivilege;

    /**
     * 在 {@link CrossTenant} 标注的方法/类作用域内建立读放行状态，退出后自动恢复。
     *
     * @param joinPoint AOP 连接点
     * @return 原方法返回值
     * @throws Throwable 当下游调用抛出异常时透传
     */
    @Around("@within(com.nona.inf.context.CrossTenant) || @annotation(com.nona.inf.context.CrossTenant)")
    public Object withReadBypass(ProceedingJoinPoint joinPoint) throws Throwable {
        return tenantPrivilege.withReadBypass(() -> {
            try {
                return joinPoint.proceed();
            }
            catch (RuntimeException | Error e) {
                throw e;
            }
            catch (Throwable e) {
                throw new IllegalStateException("unexpected checked exception in read-bypass action", e);
            }
        });
    }
}
