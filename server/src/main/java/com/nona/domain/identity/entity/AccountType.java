package com.nona.domain.identity.entity;

/**
 * 账号类型：同一 Account 聚合的角色值（领域模型：Account 聚合只有一个，仅承载
 * 凭证与状态——内容 = credentials + status + type；buyer 与 merchant 是同一聚合的
 * type 值，不是两类结构）。
 * <p>
 * 持久化为单表 {@code account} 的 type 列（与 username 组成联合唯一约束）；
 * 安全链角色映射：BUYER → {@code ROLE_BUYER}、SELLER → {@code ROLE_SELLER}。
 * <b>平台运营（admin）账号不属于本模型</b>——admin 账号与角色是 RBAC 问题
 * （Role/Permission/Assignment，属 Phase-II），本枚举不为其设计落点；
 * portal=ADMIN 的查询按 type 定向查不到即拒绝（fail-closed）。
 *
 * @author nona9961
 */
public enum AccountType {

    /**
     * 买家（商城端，对应登录门户 MALL）
     */
    BUYER,

    /**
     * 商家（商家后台端，对应登录门户 SELLER；领域模型中的 merchant）
     */
    SELLER
}
