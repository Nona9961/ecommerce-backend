package com.nona.inf.replica;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写后窗口判定单元测试（TD-08 3s 业务窗口：键剩余 TTL &gt; 2s ⟺ 写入
 * 未满 3s；秒级精度边界 ±1s 属预期）。
 * <p>
 * 全程 mock StringRedisTemplate，不依赖真实 Redis。红阶段：判定实现
 * 缺失（UnsupportedOperationException），失败原因 = 实现缺失。
 *
 * @author nona9961
 */
class RedisSearchWriteWindowUnitTest {

    /**
     * 被测窗口判定器
     */
    private RedisSearchWriteWindow window;

    /**
     * mock 的 Redis 模板
     */
    private StringRedisTemplate template;

    /**
     * 写者账号
     */
    private static final long UID = 1001L;

    /**
     * 初始化 mock 与被测对象。
     * <p>
     * 装配说明（绿阶段机械修正）：被测对象以构造器注入 mock 模板——
     * 红线阶段骨架无构造器可直接 new，mock 模板随之悬空；绿阶段实现
     * 为构造器注入形态，测试补传注入（用例语义不变）。
     */
    @BeforeEach
    void setUp() {
        template = mock(StringRedisTemplate.class);
        window = new RedisSearchWriteWindow(template);
    }

    /**
     * happy：写入未满 3s（TTL 剩余 4s/3s）判窗口内——写者本人立即可见。
     */
    @Test
    @DisplayName("窗口内：剩余 TTL 4s 判定窗口内")
    void withinWindow_ttl4_returnsTrue() {
        when(template.getExpire("lastWrite:" + UID)).thenReturn(4L);
        assertThat(window.isWithinWriteWindow(UID)).isTrue();
    }

    /**
     * happy：窗口内——TTL 3s（写入约 2s）。
     */
    @Test
    @DisplayName("窗口内：剩余 TTL 3s 判定窗口内")
    void withinWindow_ttl3_returnsTrue() {
        when(template.getExpire("lastWrite:" + UID)).thenReturn(3L);
        assertThat(window.isWithinWriteWindow(UID)).isTrue();
    }

    /**
     * critical：窗口边界——TTL 剩余 2s 判定窗口外（写入恰 3s）。
     */
    @Test
    @DisplayName("窗口边界：剩余 TTL 2s 判定窗口外")
    void withinWindow_ttl2_returnsFalse() {
        when(template.getExpire("lastWrite:" + UID)).thenReturn(2L);
        assertThat(window.isWithinWriteWindow(UID)).isFalse();
    }

    /**
     * critical：键不存在（未写过/已过期清除）判定窗口外。
     */
    @Test
    @DisplayName("键不存在：getExpire -2 判定窗口外")
    void withinWindow_keyMissing_returnsFalse() {
        when(template.getExpire("lastWrite:" + UID)).thenReturn(-2L);
        assertThat(window.isWithinWriteWindow(UID)).isFalse();
    }

    /**
     * critical：键已过期（TTL -1）判定窗口外。
     */
    @Test
    @DisplayName("键已过期：getExpire -1 判定窗口外")
    void withinWindow_expired_returnsFalse() {
        when(template.getExpire("lastWrite:" + UID)).thenReturn(-1L);
        assertThat(window.isWithinWriteWindow(UID)).isFalse();
    }

    /**
     * fail：Redis 故障——检查抛异常按窗口外处理（降级走 PG 读库），
     * 不得抛出（可用性不依赖本组件）。
     */
    @Test
    @DisplayName("Redis 故障：检查异常降级窗口外且不抛出")
    void withinWindow_redisDown_returnsFalseWithoutThrowing() {
        when(template.getExpire(anyString()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        assertThatCode(() -> window.isWithinWriteWindow(UID)).doesNotThrowAnyException();
        assertThat(window.isWithinWriteWindow(UID)).isFalse();
    }
}