package com.nona.inf.security;

import com.nona.api.auth.Portal;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 无状态 JWT 提供器：HS256 签发与校验。
 * <p>
 * claim 只含定位信息 {@code {uid, portal, exp}}，不含用户信息快照——用户状态/角色
 * 一律经上下文缓存或 DB 实时获取，token 自身不携带任何可裁决业务数据。
 * 密钥长度在构造期校验（HS256 最小 32 字节），配置缺失/过短直接启动失败。
 *
 * @author nona9961
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * HS256 最小密钥字节数（256 bit）
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 安全配置（密钥与有效期）
     */
    private final SecurityProperties properties;

    /**
     * HMAC 签名密钥
     */
    private final SecretKey secretKey;

    /**
     * 构造提供器：校验密钥长度并派生签名密钥。
     *
     * @param properties 安全配置
     */
    public JwtTokenProvider(SecurityProperties properties) {
        this.properties = properties;
        final byte[] secretBytes = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("jwt secret 不得短于 32 字节（HS256 最小安全长度）");
        }
        this.secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    /**
     * 签发无状态 JWT。
     *
     * @param uid    用户 ID
     * @param portal 登录门户
     * @return 序列化 token
     */
    public String issueToken(Long uid, Portal portal) {
        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("uid", uid)
                .claim("portal", portal.name())
                .expirationTime(Date.from(Instant.now().plusSeconds(properties.jwtTtlSeconds())))
                .build();
        final SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secretKey));
            return jwt.serialize();
        }
        catch (JOSEException e) {
            throw new IllegalStateException("jwt 签名失败", e);
        }
    }

    /**
     * 解析并校验 token：签名、过期时间与 claim 形态全部合法才返回定位信息。
     *
     * @param token 序列化 token
     * @return 定位信息；签名非法/已过期/claim 缺失或非法返回空（不做失败原因区分，避免信息泄露）
     */
    public Optional<TokenClaims> parse(String token) {
        try {
            final SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secretKey))) {
                return Optional.empty();
            }
            final JWTClaimsSet claims = jwt.getJWTClaimsSet();
            final Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                return Optional.empty();
            }
            final Object uidClaim = claims.getClaim("uid");
            if (!(uidClaim instanceof Number)) {
                return Optional.empty();
            }
            final Portal portal = resolvePortal(claims.getStringClaim("portal"));
            if (portal == null) {
                return Optional.empty();
            }
            return Optional.of(new TokenClaims(((Number) uidClaim).longValue(), portal, expiration.toInstant()));
        }
        catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    /**
     * 解析门户 claim；缺失或非法返回 null。
     *
     * @param portalName claim 值
     * @return 门户枚举；非法返回 null
     */
    private static Portal resolvePortal(String portalName) {
        if (portalName == null) {
            return null;
        }
        try {
            return Portal.valueOf(portalName);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}