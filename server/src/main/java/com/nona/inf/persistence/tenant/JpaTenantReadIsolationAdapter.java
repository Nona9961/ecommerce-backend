package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.context.TenantPrivilege;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JPA/Hibernate 读隔离适配层（设计 D2）：数据访问前按当前租户状态启停 Hibernate 租户 filter。
 * <p>
 * 覆盖两种 session 时序（原双保险合流于此，行为零变化）：
 * <ul>
 *   <li>线程已绑定 EntityManager（已定型 session）：按当前状态 disable / enable + setParameter——
 *       filter 是 session 级 map（{@code LoadQueryInfluencers.enabledFilters}），切换后下一次查询即按新状态生效</li>
 *   <li>无绑定 EM（无事务/无 session）：空操作——该场景由 resolver 在 session 打开时自查（第一重保险）覆盖</li>
 * </ul>
 * 本实现无状态、可重入、幂等（重复 disable 无害；重复 enable + setParameter 覆盖参数）。
 *
 * @author nona9961
 */
@Component
@RequiredArgsConstructor
@ScaffoldGenerated
public class JpaTenantReadIsolationAdapter implements TenantReadIsolationAdapter {

    /**
     * Hibernate {@code @TenantId} 租户 filter 名称（项目自有常量，R4）。
     * <p>
     * 契约：值必须与 Hibernate 内部 `TenantIdBinder.FILTER_NAME` 一致（当前为 {@code "_tenantId"}；
     * 该类位于 internal 包，无公开常量可引用）。升级 Hibernate 时必须按源码查证
     * （{@code AbstractSharedSessionContract#setUpMultitenancy} 中该常量的引用点）；
     * 本常量的回归比对由 {@code JpaTenantReadIsolationAdapterConstantsTest} 钉住。
     */
    static final String TENANT_ID_FILTER_NAME = "_tenantId";

    /**
     * 租户 filter 参数名（项目自有常量，R4）。
     * <p>
     * 契约：值必须与 Hibernate 内部 `TenantIdBinder.PARAMETER_NAME` 一致（当前为 {@code "tenantId"}）；
     * 升级查证与回归比对同上。
     */
    static final String TENANT_ID_PARAMETER_NAME = "tenantId";

    private final EntityManagerFactory entityManagerFactory;

    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 应用当前读隔离状态：读放行 → 禁用租户 filter；正常 → 启用并设置当前租户参数。
     * <p>
     * re-enable 返回的 filter 参数为空（{@code LoadQueryInfluencers#enableFilter} 构造新
     * {@code FilterImpl}），必须显式 {@code setParameter}——否则过滤条件无法构造，查询会
     * 静默失效（Hibernate 已知行为）。
     */
    @Override
    public void applyReadIsolation() {
        if (!TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            return;
        }
        final EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
        final Session session = holder.getEntityManager().unwrap(Session.class);
        if (TenantPrivilege.isAnyReadBypassActive()) {
            session.disableFilter(TENANT_ID_FILTER_NAME);
            return;
        }
        session.enableFilter(TENANT_ID_FILTER_NAME)
                .setParameter(TENANT_ID_PARAMETER_NAME, tenantContextAccessor.getTenantIDOrMissing());
    }
}
