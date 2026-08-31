package com.nona.domain.identity.entity;

/**
 * 账号状态（身份域）：账号可用性的业务裁决依据。
 * <p>
 * 与安全链 {@link com.nona.inf.security.AccountStatus} 语义对齐（NORMAL 对应 ACTIVE），
 * 映射由 JPA 回填实现承担；封禁账号拒绝登录，携带有效 token 的后续请求由认证过滤器统一 403。
 *
 * @author nona9961
 */
public enum AccountStatus {

    /**
     * 正常：允许登录与访问受保护接口
     */
    NORMAL,

    /**
     * 封禁：拒绝登录，携带有效 token 的后续请求一律 403
     */
    BANNED
}
