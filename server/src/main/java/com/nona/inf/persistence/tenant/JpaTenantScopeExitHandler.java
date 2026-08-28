package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.TenantPrivilege;
import com.nona.tenant.TenantScopeExitHandler;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
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
 * 自注册先例：{@link TenantContextAccessor#registerToTenantPrivilege()}（静态 volatile + {@code @PostConstruct}）。
 *
 * @author nona9961
 */
@Component
@RequiredArgsConstructor
@ScaffoldGenerated
public class JpaTenantScopeExitHandler implements TenantScopeExitHandler {

    private final EntityManagerFactory entityManagerFactory;

    /**
     * 注册自身到 {@link TenantPrivilege}（作用域退出通知），幂等；纯单测环境无本组件 → handler 保持 null。
     */
    @PostConstruct
    void register() {
        TenantPrivilege.registerScopeExitHandler(this);
    }

    @Override
    public void onScopeExited() {
        if (!TransactionSynchronizationManager.hasResource(entityManagerFactory)) {
            // 无绑定 EM → 无缓存可清（hasResource 先于 getResource，防 ClassCast）
            return;
        }
        final EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
        final EntityManager em = holder.getEntityManager();
        em.flush();  // ① 挂起写先落库（不清丢写）
        em.clear();  // ② 一级缓存失效（缓存与视角一致）
    }
}