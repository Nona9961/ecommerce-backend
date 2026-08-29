package com.nona.inf.security;

import java.util.List;

/**
 * 用户上下文：认证过滤器组装 ThreadContext 与授权裁决所需的最小信息集。
 * <p>
 * 该值对象同时是 Redis 缓存（JSON）与 DB SPI 的返回形态；roles 为角色名列表
 * （与 {@link AuthRole#name()} 对齐）。
 *
 * @param status 账号状态（裁决封禁）
 * @param roles  角色名列表
 * @author nona9961
 */
public record AuthUserContext(
        AccountStatus status,
        List<String> roles
) {
}