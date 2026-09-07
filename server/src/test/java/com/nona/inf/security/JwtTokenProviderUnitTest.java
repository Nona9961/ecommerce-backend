package com.nona.inf.security;

import com.nona.api.auth.Portal;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发/校验单元测试：往返、过期、篡改、密钥隔离与配置约束。
 */
class JwtTokenProviderUnitTest {

    /**
     * 测试专用密钥（≥32 字节，满足 HS256 要求）
     */
    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789";

    /**
     * 测试配置：2h 有效期、1h 缓存 TTL
     */
    private static final SecurityProperties PROPERTIES = new SecurityProperties(SECRET, 7200, 3600);

    /**
     * 被测提供器
     */
    private final JwtTokenProvider provider = new JwtTokenProvider(PROPERTIES);

    /**
     * 签发后解析应还原 uid 与 portal（三个门户全覆盖）。
     */
    @Test
    void issueThenParseRoundTrip_allPortals() {
        for (final Portal portal : Portal.values()) {
            final String token = provider.issueToken(1001L, portal);
            final Optional<TokenClaims> parsed = provider.parse(token);
            assertThat(parsed).isPresent();
            assertThat(parsed.get().uid()).isEqualTo(1001L);
            assertThat(parsed.get().portal()).isEqualTo(portal);
        }
    }

    /**
     * 签发后解析应还原 uid 的边界值（Long 全量范围）。
     */
    @Test
    void issueThenParseRoundTrip_uidBoundaryValues() {
        final long[] uids = {Long.MIN_VALUE, Long.MAX_VALUE, 0L};
        for (final long uid : uids) {
            final Optional<TokenClaims> parsed = provider.parse(provider.issueToken(uid, Portal.MALL));
            assertThat(parsed).isPresent();
            assertThat(parsed.get().uid()).isEqualTo(uid);
        }
    }

    /**
     * 过期 token 解析应返回空（过期时间戳早于当前时间）。
     */
    @Test
    void parseExpiredToken_returnsEmpty() throws Exception {
        final String expired = signWithExpiration(Instant.now().minusSeconds(60), "MALL", 1001L);
        assertThat(provider.parse(expired)).isEmpty();
    }

    /**
     * 篡改 payload（更换 uid）后解析应返回空（签名校验失败）。
     */
    @Test
    void parseTamperedPayload_returnsEmpty() {
        final String token = provider.issueToken(1001L, Portal.MALL);
        final String[] parts = token.split("\\.");
        final String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        final String forgedPayload = payload.replace("\"uid\":1001", "\"uid\":9999");
        final String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(provider.parse(forged)).isEmpty();
    }

    /**
     * 其他密钥签发的 token 解析应返回空。
     */
    @Test
    void parseTokenSignedWithOtherSecret_returnsEmpty() throws Exception {
        final String otherSecret = "another-secret-0123456789abcdef0123456789";
        final String token = signWithSecret(otherSecret, "MALL", 1001L, Instant.now().plusSeconds(7200));
        assertThat(provider.parse(token)).isEmpty();
    }

    /**
     * 非 JWT 结构的垃圾输入解析应返回空（不抛异常）。
     */
    @Test
    void parseGarbageInput_returnsEmpty() {
        assertThat(provider.parse("not-a-jwt")).isEmpty();
        assertThat(provider.parse("")).isEmpty();
    }

    /**
     * portal claim 为非法枚举值时应返回空（防伪造门户）。
     */
    @Test
    void parseMalformedPortalClaim_returnsEmpty() throws Exception {
        final String token = signWithClaims("HACKER", 1001L, Instant.now().plusSeconds(7200));
        assertThat(provider.parse(token)).isEmpty();
    }

    /**
     * 缺少过期时间的 token 解析应返回空。
     */
    @Test
    void parseTokenWithoutExpiration_returnsEmpty() throws Exception {
        final String token = signWithoutExpiration("MALL", 1001L);
        assertThat(provider.parse(token)).isEmpty();
    }

    /**
     * 密钥短于 32 字节时应拒绝启动（HS256 最小安全长度）。
     */
    @Test
    void shortSecret_rejectedAtConstruction() {
        assertThatThrownBy(() -> new JwtTokenProvider(new SecurityProperties("too-short", 7200, 3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 用指定密钥签发带过期时间的 token（测试辅助）。
     *
     * @param secretKey 密钥
     * @param portal    门户名
     * @param uid       用户 ID
     * @param expiresAt 过期时间
     * @return 序列化 token
     * @throws Exception 签名失败
     */
    private static String signWithSecret(String secretKey, String portal, long uid, Instant expiresAt) throws Exception {
        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("uid", uid)
                .claim("portal", portal)
                .expirationTime(Date.from(expiresAt))
                .build();
        final SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(secretKey.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    /**
     * 用默认密钥签发指定过期时间的 token（测试辅助）。
     *
     * @param expiresAt 过期时间
     * @param portal    门户名
     * @param uid       用户 ID
     * @return 序列化 token
     * @throws Exception 签名失败
     */
    private static String signWithExpiration(Instant expiresAt, String portal, long uid) throws Exception {
        return signWithSecret(SECRET, portal, uid, expiresAt);
    }

    /**
     * 用默认密钥签发缺失过期时间的 token（测试辅助）。
     *
     * @param portal 门户名
     * @param uid    用户 ID
     * @return 序列化 token
     * @throws Exception 签名失败
     */
    private static String signWithoutExpiration(String portal, long uid) throws Exception {
        final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("uid", uid)
                .claim("portal", portal)
                .build();
        final SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    /**
     * 用默认密钥签发指定 portal 的 token（测试辅助）。
     *
     * @param portalName portal 值
     * @param uid        用户 ID
     * @param expiresAt  过期时间
     * @return 序列化 token
     * @throws Exception 签名失败
     */
    private static String signWithClaims(String portalName, long uid, Instant expiresAt) throws Exception {
        return signWithSecret(SECRET, portalName, uid, expiresAt);
    }
}