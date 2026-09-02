package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.po.identity.PermissionPO;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 权限点 JPA 仓储（permission 表，global）：权限点编码唯一约束由表约束兜底。
 *
 * @author nona9961
 */
public interface PermissionJpaRepository extends JpaRepository<PermissionPO, Long> {
}
