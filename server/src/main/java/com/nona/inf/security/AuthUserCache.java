package com.nona.inf.security;

import java.util.Optional;

/**
 * 用户上下文缓存端口：按 uid 存取 {@link AuthUserContext}（Redis 实现，TTL 兜底）。
 * <p>
 * 契约语义：
 * <ul>
 *   <li>{@link #get} 返回空 = miss（无论缓存未命中还是缓存故障降级），由调用方走 DB 回填</li>
 *   <li>{@link #put} 尽力而为：写入失败不得抛出（缓存是加速层，可用性不依赖它）</li>
 *   <li>{@link #delete} 尽力而为：主动失效（关系变更如入驻通过/封店时调用，
 *       使账号-店铺关系或账号状态变化于下一请求即可见），
 *       删除失败不得抛出——缓存是加速层，残留条目由 TTL 兜底，认证链路不因缓存故障中断</li>
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

    /**
     * 主动失效用户上下文（关系变更即时生效路径：入驻审核通过/封店等账号-店铺
     * 关系或账号状态变化后调用，下一次请求 miss 回填即为新状态；删除失败
     * 静默降级，残留缓存由 TTL 兜底）。
     *
     * @param uid 用户 ID
     */
    void delete(Long uid);
}