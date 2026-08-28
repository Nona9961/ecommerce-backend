package com.nona.api.auth;

/**
 * 登录门户：区分三个独立的前端与角色空间。
 * <p>
 * 认证过滤器链按门户路由：/mall 对应 {@link #MALL}、/seller 对应 {@link #SELLER}、
 * /admin 对应 {@link #ADMIN}；买家与商家账号命名空间相互独立，登录按门户定向查表。
 */
public enum Portal {

    /**
     * 买家商城端
     */
    MALL,

    /**
     * 商家后台端
     */
    SELLER,

    /**
     * 平台后台端
     */
    ADMIN
}
