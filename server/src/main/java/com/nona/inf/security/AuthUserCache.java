package com.nona.inf.security;

import java.util.Optional;

/**
 * 用户上下文缓存端口：按 uid 存取 {@link AuthUserContext}（一期 Redis 实现，TTL 兜底）。
 * <p>
 * 契约语义：
 * <ul>
 *   <li>{@link #get} 返回空 = miss（无论缓存未命中还是缓存故障降级），由调用方走 DB 回填</li>
 *   <li>{@link #put} 尽力而为：写入失败不得抛出（缓存是加速层，可用性不依赖它）</li>
 * </ul>
 * @author nona9961
 */
public interface AuthUserCache {

    /**
     * 按 uid 读取用户上下文。
     *
     * @param uid 用户 ID
     * @return 用户上下文；未命中（含缓存故障降级）返回空
     */
    Optional<AuthUserContext> get(Long uid);

    /**
     * 回填用户上下文（miss 且 DB 查得后的写回路径）。
     *
     * @param uid     用户 ID
     * @param context 用户上下文
     */
    void put(Long uid, AuthUserContext context);
}