package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;

/**
 * 存储无关的租户读隔离适配层。
 * <p>
 * 能力契约（四能力点，存储实现各自落实）：
 * <ol>
 *   <li><b>读过滤（默认生效）</b>：tenant-scoped 读默认按当前租户过滤（fail-closed）。
 *       JPA 实现 = {@code @TenantId} + resolver + filter；MyBatis 实现 = SQL 条件注入。</li>
 *   <li><b>读放行（显式绕过）</b>：{@link com.nona.inf.context.TenantPrivilege#isAnyReadBypassActive()}
 *       为 {@code true} 时关闭过滤（提权或 {@code @CrossTenant} 作用域）。</li>
 *   <li><b>写门禁（注入/校验）</b>：由核心层 {@code TenantRepositoryAspect} 保证（存储无关），
 *       本接口不涉及——任何存储实现共享同一写门禁。</li>
 *   <li><b>提权作用域（写放行生命周期）</b>：提权状态由核心层承载，本接口仅通过状态自查感知，
 *       无生命周期回调。</li>
 * </ol>
 * 适配层在每次数据访问前被数据访问拦截点调用（自查模式）：读取当前租户状态并应用过滤，
 * 与 MyBatis SQL 拦截器同构；核心层不依赖本接口的任何实现类。
 *
 * @author nona9961
 */
@ScaffoldGenerated
public interface TenantReadIsolationAdapter {

    /**
     * 在每次数据访问前应用当前读隔离状态：
     * 任一读放行作用域激活 → 关闭过滤（全租户可见）；否则 → 恢复单租户过滤并按当前租户设置参数。
     * <p>
     * 时序契约：调用方保证本方法在每次 tenant-scoped 数据访问（查询/写入路径）前执行；
     * 无已绑定 EntityManager（无事务/无 session）时实现应空操作——该场景由 session 打开时的
     * resolver 自查（第一重保险）覆盖。实现必须可重入、幂等。
     * <p>
     * TODO：接口形态与能力契约偏差——四能力点（读过滤/读放行/写门禁/提权生命周期）
     * 仅由本单方法语义承载，签名未显式表达能力边界，契约靠 javadoc 而非签名保证。
     * 重构方向：能力点显式化为接口方法（与未来 MyBatis 实现形态对齐后落地）。
     */
    void applyReadIsolation();
}
