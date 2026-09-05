package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Hibernate 多租户配置：注册 {@link TrackingContextTenantIdentifierResolver} 到 Hibernate 属性中。
 *
 * @author nona9961
 */
@Configuration
@RequiredArgsConstructor
@ScaffoldGenerated
public class HibernateMultiTenancyConfig {

    private final TrackingContextTenantIdentifierResolver tenantIdentifierResolver;

    /**
     * 注册 Hibernate 的 tenant identifier resolver，用于 discriminator multi-tenancy（{@code @TenantId}）。
     *
     * @return HibernatePropertiesCustomizer
     */
    @Bean
    public HibernatePropertiesCustomizer multiTenancyHibernatePropertiesCustomizer() {
        return (Map<String, Object> hibernateProperties) ->
                hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
    }
}