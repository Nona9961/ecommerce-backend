package com.nona;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用启动冒烟测试：验证 Spring 上下文（含多租户/变更追踪/安全自动配置）可完整装配。
 */
@SpringBootTest(classes = EcommerceApplication.class)
class EcommerceApplicationAcTest {

    /**
     * 上下文加载成功即视为骨架可用。
     */
    @Test
    void contextLoads() {
    }
}
