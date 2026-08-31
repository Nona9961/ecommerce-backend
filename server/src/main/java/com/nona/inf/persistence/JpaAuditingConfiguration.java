package com.nona.inf.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 审计配置：启用 Spring Data JPA auditing。
 * <p>
 * 使 {@code BasePO} 的 {@code @CreatedDate}/{@code @LastModifiedDate} 在持久化时
 * 自动填充（字段为 null 时才填充创建时间，手动设置的值保留）；业务仓储
 * （如 AccountRepositoryImpl）无需自行维护审计时间戳。
 *
 * @author nona9961
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfiguration {
}
