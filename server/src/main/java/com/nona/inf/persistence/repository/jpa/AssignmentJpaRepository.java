package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.AssignmentPO;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 账号-角色分配 JPA 仓储（assignment 表，global）：同账号同角色至多一条
 * 分配（uk_assignment_account_role 联合唯一约束兜底）。
 *
 * @author nona9961
 */
public interface AssignmentJpaRepository extends JpaRepository<AssignmentPO, Long> {
}
