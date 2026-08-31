package com.nona.inf.security;

import java.util.List;

/**
 * 用户上下文：认证过滤器组装 ThreadContext 与授权裁决所需的最小信息集。
 * <p>
 * 该值对象同时是 Redis 缓存（JSON）与 DB SPI 的返回形态；roles 为角色名列表
 * （与 {@link AuthRole#name()} 对齐），shopIds 为账号关联的店铺 ID 列表
 * （账号-店铺关联，商家登录时读取写入；买家/平台端恒为空列表，一期商家恒 1 个）。
 * 兼容旧缓存 JSON（无 shopIds 字段）：compact constructor 将缺失字段归一为空列表。
 *
 * @param status  账号状态（裁决封禁）
 * @param roles   角色名列表
 * @param shopIds 关联店铺 ID 列表
 * @author nona9961
 */
public record AuthUserContext(
        AccountStatus status,
        List<String> roles,
        List<Long> shopIds
) {

    /**
     * 紧凑构造：缺失字段归一为空列表，保证下游不感知空值。
     */
    public AuthUserContext {
        roles = roles == null ? List.of() : List.copyOf(roles);
        shopIds = shopIds == null ? List.of() : List.copyOf(shopIds);
    }
}
