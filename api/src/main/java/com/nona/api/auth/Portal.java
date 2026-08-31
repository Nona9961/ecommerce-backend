package com.nona.api.auth;

/**
 * 登录门户：区分三个独立的前端与角色空间。
 * <p>
 * 认证过滤器链按门户路由：/mall 对应 {@link #MALL}、/seller 对应 {@link #SELLER}、
 * /admin 对应 {@link #ADMIN}；买家与商家账号在同一张 account 表内按 type 区分命名空间，
 * 登录按门户定向查询（type 匹配）。平台运营（admin）账号一期无落点
 * （RBAC 属 Phase-II），portal=ADMIN 定向查询不命中即拒绝（fail-closed）。
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
