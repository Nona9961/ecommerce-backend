package com.nona.inf.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户上下文缓存主动失效（{@link AuthUserCache#delete}，关系变更即时生效
 * 路径）单元测试：按 uid 精确删除 key、Redis 故障静默降级（删除失败不得抛出，
 * 残留缓存由 TTL 兜底——与 get/put 同族降级语义）。
 * <p>
 * 全程 mock StringRedisTemplate，不依赖真实 Redis。
 *
 * @author nona9961
 */
class RedisAuthUserCacheEvictionTest {

    /**
     * 测试配置：TTL 1h
     */
    private static final SecurityProperties PROPERTIES =
            new SecurityProperties("unit-test-secret-0123456789abcdef0123456789", 7200, 3600);

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
     * happy：按 uid 精确删除对应缓存 key（前缀 + uid）。
     */
    @Test
    @DisplayName("主动失效按 uid 精确删除缓存 key")
    void delete_removesKeyByUidPrefix() {
        cache.delete(1001L);

        verify(template).delete("auth:user:1001");
    }

    /**
     * error：Redis 故障时删除静默降级（不得抛出，认证链路不因缓存故障中断）。
     */
    @Test
    @DisplayName("Redis 故障时主动失效静默降级")
    void delete_redisFailureSilentlyDegrades() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(template).delete("auth:user:1001");

        assertThatCode(() -> cache.delete(1001L)).doesNotThrowAnyException();
    }
}