package com.nona.inf.security;

import com.nona.api.auth.Portal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 签发服务单元测试：签发结果契约（Phase 1 骨架态，预期红）。
 * <p>
 * 断言对准 TD-01 契约：签发 token 可被现有 {@link JwtTokenProvider} 解析，
 * claim 只含定位信息（uid/portal）且未过期——签发实现必须与现有密钥体系互通。
 */
class JwtTokenServiceTest {

    /**
     * 测试安全配置（32 字节密钥 + 2h TTL + 1h 缓存 TTL）
     */
    private static final SecurityProperties PROPERTIES =
            new SecurityProperties("unit-test-secret-0123456789abcdef0123456789", 7200, 3600);

    /**
     * 被测签发服务（委托真实 JwtTokenProvider + 配置 TTL 计算过期时间）
     */
    private final JwtTokenService service =
            new JwtTokenService(new JwtTokenProvider(PROPERTIES), PROPERTIES);

    /**
     * 签发：token 可被现有提供器解析，uid/portal 与入参一致且未过期。
     */
    @Test
    void issueToken_returnsParsableTokenWithLocatingClaims() {
        final var issued = service.issueToken(1001L, Portal.MALL);

        final JwtTokenProvider provider = new JwtTokenProvider(PROPERTIES);
        final var parsed = provider.parse(issued.token());
        assertThat(parsed).isPresent();
        assertThat(parsed.get().uid()).isEqualTo(1001L);
        assertThat(parsed.get().portal()).isEqualTo(Portal.MALL);
        assertThat(parsed.get().expiresAt()).isAfter(java.time.Instant.now());
    }

    /**
     * 签发：过期时间与配置 TTL 对齐（2 小时窗口）。
     */
    @Test
    void issueToken_expiresAtAlignedWithConfiguredTtl() {
        final var issued = service.issueToken(2002L, Portal.SELLER);
        final long windowSeconds =
                java.time.Duration.between(java.time.Instant.now(), issued.expiresAt()).toSeconds();
        assertThat(windowSeconds).isBetween(7199L, 7200L);
    }
}
