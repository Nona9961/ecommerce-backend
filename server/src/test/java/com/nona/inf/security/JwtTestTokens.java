package com.nona.inf.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 认证链路测试辅助：构造过期/篡改 JWT（与 JwtTokenProvider 同一密钥体系）。
 */
public final class JwtTestTokens {

    /**
     * 私有构造：纯静态工具类。
     */
    private JwtTestTokens() {
    }

    /**
     * 构造已过期的合法签名 token（t-60s 过期）。
     *
     * @param securityProperties 安全配置（含密钥）
     * @param uid                用户 ID
     * @return 过期 token
     * @throws Exception 签名失败
     */
    public static String expired(SecurityProperties securityProperties, long uid) throws Exception {
        return signedWithExpiration(securityProperties, uid, Instant.now().minusSeconds(60));
    }

    /**
     * 篡改合法 token 的 payload（更换 uid），保留原签名。
     *
     * @param token 合法 token
     * @return 篡改后的 token（签名必然不匹配）
     */
    public static String tamperPayload(String token) {
        final String[] parts = token.split("\\.");
        final String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        final String forgedPayload = payload.replace("\"uid\":1001", "\"uid\":9999");
        final String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        return forged;
    }

    /**
     * 用安全配置中的密钥签发指定过期时间的 token。
     *
     * @param securityProperties 安全配置
     * @param uid                用户 ID
     * @param expiresAt          过期时间
     * @return 序列化 token
     * @throws Exception 签名失败
     */
    private static String signedWithExpiration(SecurityProperties securityProperties, long uid, Instant expiresAt) throws Exception {
        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("uid", uid)
                .claim("portal", "MALL")
                .expirationTime(Date.from(expiresAt))
                .build();
        final SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(securityProperties.jwtSecret().getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}