package com.nona.inf.replica;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 写后窗口标记：{@link LastWriteMarker} 的默认实现。
 * <p>
 * key 按 uid 定位（{@code lastWrite:{uid}}），写入带 TTL 兜底；故障语义与
 * 认证缓存一致——写入失败静默（窗口失效降级），检查失败按窗口外处理
 * （读库可能旧一秒，可接受；可用性不依赖本组件）。
 *
 * @author nona9961
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLastWriteMarker implements LastWriteMarker {

    /**
     * 缓存 key 前缀：{@code lastWrite:{uid}}
     */
    public static final String KEY_PREFIX = "lastWrite:";

    /**
     * 写后窗口 TTL（秒）：key 存活时长 = 写后自读窗口权威值 5s
     * （写 Redis {@code lastWrite:{uid}} TTL 5s；3s 窗口判定由搜索消费方按
     * 剩余 TTL/写入时刻实现，本组件只负责埋点与存活检查）
     */
    public static final long WINDOW_SECONDS = 5;

    /**
     * 标记占位值
     */
    private static final String MARK_VALUE = "1";

    /**
     * Redis 模板（字符串值）
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 标记写后窗口：写入带 TTL 的 key；写入故障静默失败（窗口失效 = 降级）。
     *
     * @param uid 写者用户 ID
     */
    @Override
    public void markWrite(Long uid) {
        try {
            redisTemplate.opsForValue().set(key(uid), MARK_VALUE, Duration.ofSeconds(WINDOW_SECONDS));
        }
        catch (DataAccessException e) {
            log.warn("[last-write] 写入失败（写后窗口失效，降级走 PG 读库） uid={}", uid, e);
        }
    }

    /**
     * 检查是否处于写后窗口内；检查故障按窗口外处理（降级走 PG 读库）。
     *
     * @param uid 用户 ID
     * @return 窗口内返回 true；否则返回 false
     */
    @Override
    public boolean isWithinWriteWindow(Long uid) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(uid)));
        }
        catch (DataAccessException e) {
            log.warn("[last-write] 检查失败（按窗口外处理，走 PG 读库） uid={}", uid, e);
            return false;
        }
    }

    /**
     * 组装缓存 key。
     *
     * @param uid 用户 ID
     * @return 缓存 key
     */
    private static String key(Long uid) {
        return KEY_PREFIX + uid;
    }
}
