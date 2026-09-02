package com.nona.domain.identity.entity;

/**
 * 账号-角色分配（RBAC 模型，对应 assignment 表，global）：账号与角色的多对多
 * 分配关系实体。
 * <p>
 * 账号本身为全局数据（不随租户隔离），故分配记录同样不带租户列；
 * 同一账号同一角色至多一条分配（数据库 (account_id, role_id) 联合唯一约束兜底）。
 *
 * @author nona9961
 */
public class Assignment {

    /**
     * 分配 ID（Snowflake）
     */
    private final Long id;

    /**
     * 账号 ID
     */
    private final Long accountId;

    /**
     * 角色 ID
     */
    private final Long roleId;

    /**
     * 构造账号-角色分配。
     *
     * @param id        分配 ID
     * @param accountId 账号 ID
     * @param roleId    角色 ID
     */
    public Assignment(Long id, Long accountId, Long roleId) {
        this.id = id;
        this.accountId = accountId;
        this.roleId = roleId;
    }

    /**
     * 分配 ID。
     *
     * @return 分配 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 账号 ID。
     *
     * @return 账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 角色 ID。
     *
     * @return 角色 ID
     */
    public Long getRoleId() {
        return roleId;
    }
}
