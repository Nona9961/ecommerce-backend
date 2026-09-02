package com.nona.inf.replica;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

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
 * Redis 写后窗口标记单元测试：写入带 TTL、窗口检查、故障降级语义。
 * <p>
 * 全程 mock StringRedisTemplate，不依赖真实 Redis。
 */
class RedisLastWriteMarkerTest {

    /**
     * 被测标记器
     */
    private RedisLastWriteMarker marker;

    /**
     * mock 的 Redis 模板
     */
    private StringRedisTemplate template;

    /**
     * mock 的字符串值操作
     */
    private ValueOperations<String, String> valueOperations;

    /**
     * 初始化 mock 与被测对象。
     */
    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOperations);
        marker = new RedisLastWriteMarker(template);
    }

    /**
     * 标记写入：按 uid 定位 key 并携带窗口 TTL（5 秒）。
     */
    @Test
    void markWrite_setsKeyWithWindowTtl() {
        marker.markWrite(1001L);
        verify(valueOperations).set("lastWrite:1001", "1", Duration.ofSeconds(5));
    }

    /**
     * 窗口检查命中：key 存在返回 true。
     */
    @Test
    void isWithinWriteWindow_hit_returnsTrue() {
        when(template.hasKey("lastWrite:1001")).thenReturn(true);
        assertThat(marker.isWithinWriteWindow(1001L)).isTrue();
    }

    /**
     * 窗口检查未命中：key 不存在（已过期/未写过）返回 false。
     */
    @Test
    void isWithinWriteWindow_miss_returnsFalse() {
        when(template.hasKey("lastWrite:1001")).thenReturn(false);
        assertThat(marker.isWithinWriteWindow(1001L)).isFalse();
    }

    /**
     * 标记写入故障：静默失败不得抛出（窗口失效 = 降级，可用性不依赖本组件）。
     */
    @Test
    void markWrite_redisDown_doesNotThrow() {
        doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(valueOperations).set(anyString(), any(), any(Duration.class));
        assertThatCode(() -> marker.markWrite(1001L)).doesNotThrowAnyException();
    }

    /**
     * 窗口检查故障：按窗口外处理（降级走 PG 读库），不得抛出。
     */
    @Test
    void isWithinWriteWindow_redisDown_returnsFalse() {
        when(template.hasKey(anyString())).thenThrow(new DataAccessResourceFailureException("connection refused"));
        assertThat(marker.isWithinWriteWindow(1001L)).isFalse();
    }
}
