package com.nona.inf.persistence.repository.jpa;

import com.nona.inf.persistence.tenant.TestGlobalNotePO;
import org.springframework.data.jpa.repository.JpaRepository;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 租户隔离测试用的 global repository。
 *
 * @author nona
 */
@ScaffoldGenerated
public interface TestGlobalNoteRepository extends JpaRepository<TestGlobalNotePO, Long> {
}