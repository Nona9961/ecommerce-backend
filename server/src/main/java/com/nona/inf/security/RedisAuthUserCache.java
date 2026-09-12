package com.nona.inf.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.util.JacksonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 用户上下文缓存：key 按 uid 定位（{@code auth:user:{uid}}），JSON 存储
 * {@link AuthUserContext}，TTL 由配置兜底（当前 1 小时）。
 * <p>
 * <b>可用性不依赖 Redis</b>：连接/执行故障（{@link DataAccessException}）一律降级——
 * 读取按 miss 处理（走 DB 回填），写入静默失败（尽力而为），
 * 认证链路不得因缓存故障中断。
 *
 * @author nona9961
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAuthUserCache implements AuthUserCache {

    /**
     * 缓存 key 前缀：{@code auth:user:{uid}}
     */
    public static final String KEY_PREFIX = "auth:user:";

    /**
     * JSON 序列化器（项目统一静态实例，已注册 JavaTimeModule）
     */
    private static final ObjectMapper OBJECT_MAPPER = JacksonUtil.DEFAULT_MAPPER;

    /**
     * Redis 模板（字符串值）
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 安全配置（缓存 TTL）
     */
    private final SecurityProperties properties;

    /**
     * 按 uid 读取用户上下文；缓存故障按 miss 降级。
     *
     * @param uid 用户 ID
     * @return 用户上下文；未命中或读取故障返回空
     */
    @Override
    public Optional<AuthUserContext> get(Long uid) {
        try {
            final String json = redisTemplate.opsForValue().get(key(uid));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(OBJECT_MAPPER.readValue(json, AuthUserContext.class));
        }
        catch (DataAccessException e) {
            log.warn("[auth-cache] redis 不可用，降级按 miss 处理 uid={}", uid, e);
            return Optional.empty();
        }
        catch (JsonProcessingException e) {
            log.warn("[auth-cache] 缓存数据损坏，按 miss 处理由 DB 回填自愈 uid={}", uid, e);
            return Optional.empty();
        }
    }

    /**
     * 回填用户上下文；写入故障静默失败（缓存尽力而为）。
     *
     * @param uid     用户 ID
     * @param context 用户上下文
     */
    @Override
    public void put(Long uid, AuthUserContext context) {
        try {
            redisTemplate.opsForValue().set(key(uid), OBJECT_MAPPER.writeValueAsString(context),
                    Duration.ofSeconds(properties.userContextCacheTtlSeconds()));
        }
        catch (DataAccessException | JsonProcessingException e) {
            log.warn("[auth-cache] 回填失败（缓存尽力而为，认证走 DB 直查） uid={}", uid, e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按 uid 精确删除缓存 key（前缀 + uid）；删除故障（{@link DataAccessException}）
     * 静默降级——残留条目由 TTL 兜底，关系变更于下一次请求 miss 回填时生效。
     */
    @Override
    public void delete(Long uid) {
        try {
            redisTemplate.delete(key(uid));
        }
        catch (DataAccessException e) {
            log.warn("[auth-cache] 主动失效失败（残留由 TTL 兜底） uid={}", uid, e);
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