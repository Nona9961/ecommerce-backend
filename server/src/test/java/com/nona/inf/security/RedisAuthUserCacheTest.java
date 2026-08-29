package com.nona.inf.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 用户上下文缓存单元测试：序列化往返、miss、Redis 故障降级与坏数据自愈。
 * <p>
 * 全程 mock StringRedisTemplate，不依赖真实 Redis（Redis 连接测试不属于单元范畴）。
 */
class RedisAuthUserCacheTest {

    /**
     * 测试配置：TTL 1h
     */
    private static final SecurityProperties PROPERTIES = new SecurityProperties("unit-test-secret-0123456789abcdef0123456789", 7200, 3600);

    /**
     * 活跃买家上下文
     */
    private static final AuthUserContext ACTIVE_BUYER = new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"));

    /**
     * mock 的 Redis 模板
     */
    private StringRedisTemplate template;

    /**
     * mock 的字符串值操作
     */
    private ValueOperations<String, String> valueOperations;

    /**
     * 被测缓存
     */
    private RedisAuthUserCache cache;

    /**
     * 初始化 mock 与被测对象。
     */
    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOperations);
        cache = new RedisAuthUserCache(template, PROPERTIES);
    }

    /**
     * 命中：按 key 读取并反序列化为用户上下文。
     */
    @Test
    void getHit_parsesStoredJson() {
        when(valueOperations.get("auth:user:1001")).thenReturn("{\"status\":\"ACTIVE\",\"roles\":[\"BUYER\"]}");
        final Optional<AuthUserContext> result = cache.get(1001L);
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result.get().roles()).containsExactly("BUYER");
    }

    /**
     * miss：key 不存在返回空（触发 DB 回填路径）。
     */
    @Test
    void getMiss_returnsEmpty() {
        when(valueOperations.get(anyString())).thenReturn(null);
        assertThat(cache.get(1001L)).isEmpty();
    }

    /**
     * Redis 故障：连接异常必须降级为空缓存，不得向上抛出（认证可用性不依赖缓存）。
     */
    @Test
    void getRedisDown_fallsBackToEmpty() {
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        assertThat(cache.get(1001L)).isEmpty();
    }

    /**
     * 坏 JSON：缓存数据损坏按 miss 处理，由 DB 回填自愈。
     */
    @Test
    void getCorruptJson_returnsEmpty() {
        when(valueOperations.get("auth:user:1001")).thenReturn("{not-json");
        assertThat(cache.get(1001L)).isEmpty();
    }

    /**
     * 回填：按 key 写入 JSON 并携带兜底 TTL。
     */
    @Test
    void put_writesJsonWithTtl() {
        cache.put(1001L, ACTIVE_BUYER);
        verify(valueOperations).set("auth:user:1001", "{\"status\":\"ACTIVE\",\"roles\":[\"BUYER\"]}", Duration.ofSeconds(3600));
    }

    /**
     * Redis 故障：回填失败不得抛出（缓存尽力而为，认证走 DB 直查）。
     */
    @Test
    void putRedisDown_doesNotThrow() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).set(anyString(), any(), any(Duration.class));
        assertThatCode(() -> cache.put(1001L, ACTIVE_BUYER)).doesNotThrowAnyException();
    }
}