package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.RolePO;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 角色 JPA 仓储（role 表，global）：角色编码唯一约束由表约束兜底。
 *
 * @author nona9961
 */
public interface RoleJpaRepository extends JpaRepository<RolePO, Long> {
}
