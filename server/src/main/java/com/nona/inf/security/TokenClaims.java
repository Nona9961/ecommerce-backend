package com.nona.inf.security;

import com.nona.api.auth.Portal;

import java.time.Instant;

/**
 * JWT 定位信息：解析后的 claim 内容（uid/portal/过期时间）。
 * <p>
 * 按设计只含定位信息，不含用户信息快照——用户信息一律经上下文缓存/DB 实时获取，
 * 避免 token 与账号状态脱节（封禁即时生效的前提）。
 *
 * @param uid       用户 ID
 * @param portal    登录门户
 * @param expiresAt 过期时间
 * @author nona9961
 */
public record TokenClaims(
        Long uid,
        Portal portal,
        Instant expiresAt
) {
}