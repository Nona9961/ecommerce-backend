package com.nona.inf.security;

import com.nona.api.auth.Portal;
import com.nona.domain.identity.ports.IssuedToken;
import com.nona.domain.identity.ports.TokenService;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * JWT 签发服务：{@link TokenService} 的基础设施实现。
 * <p>
 * 委托 {@link JwtTokenProvider} 签发 HS256 JWT：claim 只含定位信息
 * {@code {uid, portal, exp}}，密钥配置与解析能力完全复用现有底座；
 * expiresAt 由签发时刻 + 配置 TTL 计算（与 token 内 exp claim 同源）。
 *
 * @author nona9961
 */
@Component
public class JwtTokenService implements TokenService {

    /**
     * JWT 提供器（HS256 签发/校验）
     */
    private final JwtTokenProvider tokenProvider;

    /**
     * 安全配置（JWT TTL）
     */
    private final SecurityProperties properties;

    /**
     * 构造签发服务。
     *
     * @param tokenProvider JWT 提供器
     * @param properties    安全配置
     */
    public JwtTokenService(JwtTokenProvider tokenProvider, SecurityProperties properties) {
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IssuedToken issueToken(Long uid, Portal portal) {
        final String token = tokenProvider.issueToken(uid, portal);
        return new IssuedToken(token, Instant.now().plusSeconds(properties.jwtTtlSeconds()));
    }
}
