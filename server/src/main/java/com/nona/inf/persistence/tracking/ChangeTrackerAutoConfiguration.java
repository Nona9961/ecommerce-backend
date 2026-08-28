package com.nona.inf.persistence.tracking;

import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import com.nona.annotation.ScaffoldGenerated;

/**
 * ChangeTracker 自动配置
 * <p>
 * 当 classpath 中存在 {@link ChangeTracker} 与 {@link ChangeTrackerProvider} 时生效，
 * 根据 {@link ChangeTrackingProperties} 自动装配 {@link ChangeTrackerProvider} Bean。
 */
@AutoConfiguration
@ConditionalOnClass({ChangeTracker.class, ChangeTrackerProvider.class})
@EnableConfigurationProperties(ChangeTrackingProperties.class)
@ScaffoldGenerated
public class ChangeTrackerAutoConfiguration {

    /**
     * 创建 ChangeTrackerProvider Bean。
     *
     * @param props 变更追踪配置属性
     * @return 配置好的 ChangeTrackerProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ChangeTrackerProvider changeTrackerProvider(ChangeTrackingProperties props) {
        return ChangeTrackerProvider.builder()
                .fromProperties(props)
                .build();
    }
}
