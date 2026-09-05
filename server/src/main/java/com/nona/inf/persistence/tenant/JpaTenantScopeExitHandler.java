package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.tenant.TenantScopeExitHandler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 「缓存与视角一致」的 JPA 形态：作用域退出 → {@code flush()+clear()}。
 * <p>
 * 提权/读放行作用域退出时（含异常路径）清理当前线程绑定的 EntityManager 一级缓存：
 * <ol>
 *   <li>{@link EntityManager#flush()} — 挂起写先落库（否则 clear 丢写）</li>
 *   <li>{@link EntityManager#clear()} — 一级缓存失效（放行阶段读入的异租户实体不再滞留，
 *       过滤恢复后查询重新发 SQL、filter 生效）</li>
 * </ol>
 * 顺序即正确性：先落库保写，再失效缓存。
 * <p>
 * 无绑定 EM（无事务/无 session）→ 空操作（缓存随事务消亡，无泄露可清）；等价于
 * {@code elevatedInTransaction} 场景（作用域退出晚于事务提交，EM 已解绑——空转属预期）。
 * <p>
 * 注册机制：本类为普通 {@code @Component}，由 Spring 容器收集注入
 * {@code TenantPrivilege} 的 {@code List<TenantScopeExitHandler>}——每个容器收集自己的
 * 列表，多容器并存互不覆盖（每个容器按收集注入自身完整的退出处理器列表）。
 * <p>
 * 依赖延迟：{@link EntityManagerFactory} 经 {@link ObjectProvider} 注入，构造期不解析
 * （仅在 {@link #onScopeExited()} 首次调用时解析）——打破初始化期循环依赖：
 * {@code EntityManagerFactory → HibernateMultiTenancyConfig → TrackingContextTenantIdentifierResolver
 * → TenantPrivilege → JpaTenantScopeExitHandler → EntityManagerFactory}。
 * {@code onScopeExited} 仅在运行时作用域退出时触发，彼时 EMF 必然已就绪。
 *
 * @author nona9961
 */
@Component
@RequiredArgsConstructor
@ScaffoldGenerated
public class JpaTenantScopeExitHandler implements TenantScopeExitHandler {

    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;

    @Override
    public void onScopeExited() {
        final EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getObject();
        if (!TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            return;
        }
        final EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
        final EntityManager em = holder.getEntityManager();
        em.flush();
        em.clear();
    }
}