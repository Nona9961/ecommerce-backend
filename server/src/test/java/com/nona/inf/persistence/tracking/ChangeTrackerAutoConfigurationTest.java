package com.nona.inf.persistence.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.nona.annotation.ScaffoldGenerated;

/**
 * ChangeTracker自动配置测试
 */
@ScaffoldGenerated
class ChangeTrackerAutoConfigurationTest {

    /**
     * 场景1：用户未定义Bean时自动创建
     */
    @Test
    void shouldCreateProviderWhenNoUserDefined() {
        final ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.setDefaultIdentifier("id");
        props.getValueTypePackages().add("com.example.vo");

        final ChangeTrackerAutoConfiguration config = new ChangeTrackerAutoConfiguration();
        final ChangeTrackerProvider provider = config.changeTrackerProvider(props);

        assertNotNull(provider);
    }

    /**
     * 场景2：从配置加载值对象包
     */
    @Test
    void shouldLoadValuePackagesFromProperties() {
        final ChangeTrackingProperties props = new ChangeTrackingProperties();
        props.setDefaultIdentifier("id");
        props.getValueTypePackages().add("com.example.vo");

        final ChangeTrackerAutoConfiguration config = new ChangeTrackerAutoConfiguration();
        final ChangeTrackerProvider provider = config.changeTrackerProvider(props);

        assertTrue(provider.getValuePackages().contains("com.example.vo"));
    }
}
