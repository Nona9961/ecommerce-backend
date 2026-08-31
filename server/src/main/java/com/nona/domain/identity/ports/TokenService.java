package com.nona.domain.identity.ports;

import com.nona.api.auth.Portal;

/**
 * 令牌签发端口（identity 域 ports）：签发无状态 JWT。
 * <p>
 * claim 只含定位信息 {@code {uid, portal, exp}}，不含用户信息快照（无状态 JWT 仅承载定位 claim）；
 * 落本域端口、由基础设施实现（inf.security.JwtTokenService，复用
 * JwtTokenProvider 现有密钥配置与 HS256 能力）。
 *
 * @author nona9961
 */
public interface TokenService {

    /**
     * 签发 JWT。
     *
     * @param uid    用户 ID
     * @param portal 登录门户
     * @return 签发结果（令牌 + 过期时间）
     */
    IssuedToken issueToken(Long uid, Portal portal);
}
