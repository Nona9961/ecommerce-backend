package com.nona.inf.security;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版账号状态提供器（测试支撑）：DB SPI 的替代实现，记录查询次数以验证缓存命中即免查。
 * <p>
 * 正式实现由身份域落地（账号表 + JPA 查询），本类仅用于认证链路测试。
 */
public class InMemoryAccountStatusProvider implements AccountStatusProvider {

    /**
     * uid → 用户上下文
     */
    private final Map<Long, AuthUserContext> users = new ConcurrentHashMap<>();

    /**
     * 查询次数（验证缓存命中时不触发 DB 查询）
     */
    private final AtomicInteger loadCount = new AtomicInteger();

    /**
     * 按 uid 查询用户上下文（模拟 DB 查询）。
     *
     * @param uid 用户 ID
     * @return 用户上下文；用户不存在返回空
     */
    @Override
    public Optional<AuthUserContext> loadUserContext(Long uid) {
        loadCount.incrementAndGet();
        return Optional.ofNullable(users.get(uid));
    }

    /**
     * 注册用户上下文（模拟 DB 中存在的账号）。
     *
     * @param uid     用户 ID
     * @param context 用户上下文
     */
    public void register(Long uid, AuthUserContext context) {
        users.put(uid, context);
    }

    /**
     * 累计查询次数。
     *
     * @return 查询次数
     */
    public int loadCount() {
        return loadCount.get();
    }

    /**
     * 重置查询计数（测试用例间隔离）。
     */
    public void reset() {
        loadCount.set(0);
    }
}