package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.TenantScopedBasePO;
import com.nona.tenant.TenantWriteGate;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 多租户隔离（规则上移 common：参数判定取代方法名匹配）
 * <p>
 * 基于 Hibernate discriminator multi-tenancy（@TenantId）实现 tenant-scoped 的自动隔离（读 fail-closed；写自动注入/校验 tenantID）。
 * <p>
 * 写门禁只与参数形态有关、与操作方法名无关：遍历参数中的 tenant-scoped 实体
 * （{@link TenantScopedBasePO} 单参 / Iterable 内 PO 元素），逐实体调用 {@link TenantWriteGate#decideInjection}
 * 执行两条件判定（提权状态 × 实体归属），按返回值执行视角补全注入。判定逻辑 100% 在 common（纯函数），
 * 本类只剩遍历与注入执行。ID/无参形态（deleteById/deleteAll()/bulk inBatch）不判，
 * 写目标合法性由 JPA discriminator filter 在目标解析阶段达成（bulk 形态契约，升级回归清单）。
 *
 * @author nona9961
 */
@Aspect
@Component
@RequiredArgsConstructor
@ScaffoldGenerated
public class TenantRepositoryAspect {

    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 租户提权/读放行作用域状态（构造注入的 bean；作用域退出处理器按容器收集）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 存储无关的租户读隔离适配层（JPA 实现）：每次数据访问前按当前状态启停租户 filter。
     */
    private final TenantReadIsolationAdapter tenantReadIsolationAdapter;

    /**
     * 对 Spring Data Repository 的所有访问应用租户规则：
     * 先由适配层应用读隔离状态（读放行/恢复过滤），再对参数中的 tenant-scoped 实体执行写门禁。
     *
     * @param joinPoint AOP 连接点
     * @return 原方法返回值
     * @throws Throwable 当下游调用抛出异常时透传
     */
    @Around("this(org.springframework.data.repository.Repository)")
    public Object applyTenantRules(ProceedingJoinPoint joinPoint) throws Throwable {
        tenantReadIsolationAdapter.applyReadIsolation();
        enforceTenantWriteGate(joinPoint.getArgs());
        return joinPoint.proceed();
    }

    /**
     * 参数遍历 + 门禁执行（形态归类：PO 单参/PO 集合元素 → 门禁判定；ID/无参 → filter 兜底）：
     * <ul>
     *   <li>PO 形态（门禁判定）：save/saveAndFlush/delete(entity) 单参；saveAll/deleteAll(集合)/bulk inBatch(集合) 的 Iterable 元素</li>
     *   <li>ID/无参形态（filter 兜底）：deleteById/deleteAll()/deleteAllInBatch()——参数取不到租户信息，写目标合法性由 filter 在目标解析阶段达成</li>
     * </ul>
     * Iterable 内 null/混合类型元素天然跳过（instanceof 判定）。判定上下文（contextTenant/elevated）只读一次。
     *
     * @param args 数据访问方法参数
     */
    private void enforceTenantWriteGate(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        final String contextTenant = tenantContextAccessor.getTenantID();
        final boolean elevated = tenantPrivilege.isActive();
        for (Object arg : args) {
            if (arg instanceof TenantScopedBasePO po) {
                applyGate(po, contextTenant, elevated);
            } else if (arg instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item instanceof TenantScopedBasePO po) {
                        applyGate(po, contextTenant, elevated);
                    }
                }
            }
        }
    }

    /**
     * 对单个 tenant-scoped 实体执行写门禁并落地视角补全注入。
     *
     * @param po           待写入实体
     * @param contextTenant 当前视角租户
     * @param elevated      是否提权（isActive() 一次性判定结果）
     * @throws com.nona.exceptions.BusinessException 空归属/哨兵/视角缺失/归属不一致 拒绝时由 {@link TenantWriteGate} 抛出
     */
    private void applyGate(TenantScopedBasePO po, String contextTenant, boolean elevated) {
        final String inject = TenantWriteGate.decideInjection(po.getTenantID(), contextTenant, elevated);
        if (inject != null) {
            po.setTenantID(inject); // 仅视角补全注入发生时写实体；放行(null)不触碰归属
        }
    }
}
