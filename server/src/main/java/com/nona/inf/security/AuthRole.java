package com.nona.inf.security;

/**
 * 当前角色空间：与路由前缀一一对应（/mall→BUYER、/seller→SELLER、/admin→ADMIN）。
 * <p>
 * 角色名以字符串存入用户上下文缓存与跟踪作用域持有者；Security 授权时按
 * {@code ROLE_<name>} 匹配。<b>RBAC 权限点扩展</b>在身份域内以同一角色名空间叠加，
 * 不对本枚举做兼容性破坏（新增角色 = 新增枚举值）。
 * @author nona9961
 */
public enum AuthRole {

    /**
     * 买家（商城端）
     */
    BUYER,

    /**
     * 商家（商家后台端）
     */
    SELLER,

    /**
     * 平台运营（平台后台端）
     */
    ADMIN
}