package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 跨租户读放行标记：标注方法（或类）内的<b>读操作</b>关闭租户过滤，
 * 按全租户可见执行。
 * <ul>
 *   <li><b>只影响读</b>：写门禁不受注解影响——写仍必须显式 {@link TenantPrivilege#elevated(Runnable)}
 *       提权，未提权的异租户写在注解作用域内照常拒绝</li>
 *   <li><b>运行时处理不混居身份上下文</b>：由 {@code CrossTenantAspect} 建立独立读放行状态
 *       （{@link TenantPrivilege#withReadBypass(Runnable)}，ScopedValue），不写请求作用域上下文</li>
 *   <li>支持已定型 session（数据访问点自查）；作用域退出即恢复；嵌套安全</li>
 * </ul>
 * 租户过滤是默认行为，本注解是显式关闭过滤的契约（不标注则默认过滤，fail-closed）。
 *
 * @author nona9961
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ScaffoldGenerated
public @interface CrossTenant {
}
