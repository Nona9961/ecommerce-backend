package com.nona.inf.persistence.po.identity;

import com.nona.inf.persistence.po.BasePO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 账号-角色分配持久化对象（assignment 表，global）：账号与角色的多对多分配。
 * <p>
 * 账号本身为全局数据（不随租户隔离），故分配记录同样不带租户列；
 * 同一账号同一角色至多一条分配（uk_assignment_account_role 联合唯一约束兜底）。
 *
 * @author nona9961
 */
@Entity
@Table(name = "assignment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_assignment_account_role", columnNames = {"account_id", "role_id"})
})
public class AssignmentPO extends BasePO {

    /**
     * 账号 ID
     */
    @Column(nullable = false, name = "account_id")
    private Long accountId;

    /**
     * 角色 ID
     */
    @Column(nullable = false, name = "role_id")
    private Long roleId;

    /**
     * 账号 ID。
     *
     * @return 账号 ID
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * 设置账号 ID。
     *
     * @param accountId 账号 ID
     */
    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    /**
     * 角色 ID。
     *
     * @return 角色 ID
     */
    public Long getRoleId() {
        return roleId;
    }

    /**
     * 设置角色 ID。
     *
     * @param roleId 角色 ID
     */
    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }
}
