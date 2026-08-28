package com.nona.api.auth;

import java.util.List;

/**
 * 登录响应体：三端共用契约。
 * <p>
 * token 为无状态 JWT（有效期 2 小时），claim 只含定位信息（用户 ID/门户/过期时间），
 * 不含用户信息快照；登出即客户端丢弃 token，服务端无会话状态。
 *
 * @param token     JWT 访问令牌
 * @param expiresAt token 过期时间（ISO 8601 字符串）
 * @param shopIds   当前账号关联的店铺 ID 列表；商家端返回（一期恒 1 个），买家/平台端为空列表
 */
public record LoginResponse(
        String token,
        String expiresAt,
        List<Long> shopIds
) {
}
