package com.nona.domain.identity.ports;

import java.time.Instant;

/**
 * 签发结果：JWT 令牌与其过期时间。
 *
 * @param token     序列化 JWT
 * @param expiresAt 过期时间
 * @author nona9961
 */
public record IssuedToken(
        String token,
        Instant expiresAt
) {
}
