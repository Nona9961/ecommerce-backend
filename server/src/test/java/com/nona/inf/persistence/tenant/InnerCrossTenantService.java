package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 测试用内层跨租户读服务：{@code @CrossTenant} 嵌套场景的内层（被 {@link CrossTenantTestService} 调用，
 * 经 Spring 代理使嵌套 AOP 生效）。
 *
 * @author nona9961
 */
@Service
@ScaffoldGenerated
class InnerCrossTenantService {

    private final TestTenantNoteRepository tenantNoteRepository;

    /**
     * 构造内层服务。
     *
     * @param tenantNoteRepository 租户隔离 note 仓库
     */
    InnerCrossTenantService(TestTenantNoteRepository tenantNoteRepository) {
        this.tenantNoteRepository = tenantNoteRepository;
    }

    /**
     * 在 {@code @CrossTenant} 作用域内查询所有 tenant-scoped note。
     *
     * @return 所有 note 列表
     */
    @CrossTenant
    List<TestTenantNotePO> listAllNotesInner() {
        return tenantNoteRepository.findAll();
    }
}
