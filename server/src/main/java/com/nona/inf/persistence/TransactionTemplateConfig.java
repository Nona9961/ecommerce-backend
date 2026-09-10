package com.nona.inf.persistence;

import com.nona.inf.persistence.tenant.JpaTenantScopeExitHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提权事务模板配置（TD-12 多租户写放行的事务边界装配）。
 * <p>
 * {@link TransactionTemplate} 以 REQUIRES_NEW 传播装配（覆盖 Spring Boot
 * 自动注册的默认 bean——REQUIRED）：{@code TenantPrivilege#elevatedInTransaction}
 * 的提权作用域与该事务边界合并为同一结构化作用域，事务在提权作用域内
 * 独立提交——作用域退出时 EM 已解绑，{@link JpaTenantScopeExitHandler}
 * 空转属预期（TenantPrivilege.elevatedInTransaction apiNote 权威依据）。
 * <p>
 * 装配缺口背景（WU-49 修订）：REQUIRED join 外层方法事务时，提权作用域
 * 退出后（ScopedValue 已解除）外层事务仍绑定 EM——scope-exit 处理器触发
 * {@code em.flush()}，此时 Hibernate 租户解析回退请求视角（买家/回调/
 * 调度上下文为 MISSING），对 tenant-scoped 待写实体（tenant=shopId）执行
 * {@code @TenantId} 校验必失败 → Session 标记 rollback-only → 外层提交抛
 * UnexpectedRollback。REQUIRES_NEW 下内层独立提交点位于提权作用域内，
 * 该时序不复存在。
 * <p>
 * 消费面：应用层用例/监听器/调度器构造注入的 TransactionTemplate 均为本
 * bean（elevatedInTransaction 唯一消费方）。回滚语义：内层失败 → 内层
 * 回滚 → 异常透传 → 外层方法事务回滚（编排原子性断言不因传播级别变化
 * 而失效——内层编排写与外层收尾写在各自边界内原子）。
 *
 * @author nona9961
 */
@Configuration
public class TransactionTemplateConfig {

    /**
     * 提权事务模板（REQUIRES_NEW：提权作用域 = 事务边界）。
     *
     * @param transactionManager 平台事务管理器（JPA）
     * @return 提权事务模板 bean
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        final TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}