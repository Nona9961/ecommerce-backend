package com.nona.inf.security;

/**
 * 账号状态：认证链路对用户可用性的唯一裁决依据。
 * <p>
 * 平台封禁 = DB 改状态 + 主动删/更缓存 → 下一请求即被拦截（{@link #BANNED} 统一 403），
 * 业务代码零侵入。
 * @author nona9961
 */
public enum AccountStatus {

    /**
     * 正常：允许登录与访问受保护接口
     */
    ACTIVE,

    /**
     * 封禁：拒绝登录，携带有效 token 的后续请求一律 403
     */
    BANNED
}