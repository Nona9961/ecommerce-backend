package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 测试用跨租户读放行服务：封装 {@code @CrossTenant} 注解场景的读写操作，供租户机制集成测试复用。
 *
 * @author nona9961
 */
@Service
@ScaffoldGenerated
class CrossTenantTestService {

    private final TestTenantNoteRepository tenantNoteRepository;

    private final InnerCrossTenantService innerCrossTenantService;

    /**
     * 构造测试服务。
     *
     * @param tenantNoteRepository  租户隔离 note 仓库
     * @param innerCrossTenantService 内层跨租户读服务（嵌套注解场景）
     */
    CrossTenantTestService(TestTenantNoteRepository tenantNoteRepository,
                           InnerCrossTenantService innerCrossTenantService) {
        this.tenantNoteRepository = tenantNoteRepository;
        this.innerCrossTenantService = innerCrossTenantService;
    }

    /**
     * 进入提权作用域执行有返回值操作（组合场景用）；repository 操作不抛受检异常，此处收拢。
     *
     * @param action 提权操作
     * @param <T>    返回类型
     * @return 操作结果
     */
    private static <T> T elevate(Callable<T> action) {
        try {
            return TenantPrivilege.elevated(action);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("unexpected checked exception in elevated action", e);
        }
    }

    /**
     * 在 {@code @CrossTenant} 作用域内查询所有 tenant-scoped note（全租户可见）。
     *
     * @return 所有 note 列表
     */
    @CrossTenant
    List<TestTenantNotePO> listAllNotes() {
        return tenantNoteRepository.findAll();
    }

    /**
     * 在 {@code @CrossTenant} 作用域内按 id 查询 note（全租户可见）。
     *
     * @param id note id
     * @return note；不存在则返回 {@code null}
     */
    @CrossTenant
    TestTenantNotePO getNote(Long id) {
        return tenantNoteRepository.findById(id).orElse(null);
    }

    /**
     * 在 {@code @CrossTenant} 作用域内写入显式异租户 note——写门禁不受注解影响，必须被拒绝（AC 钉住场景）。
     *
     * @param tenantID 目标 tenantID
     * @param id       note id
     * @param content  note 内容
     */
    @CrossTenant
    void saveForeignTenantNote(String tenantID, Long id, String content) {
        final LocalDateTime now = LocalDateTime.now();

        final TestTenantNotePO po = new TestTenantNotePO();
        po.setId(id);
        po.setTenantID(tenantID);
        po.setContent(content);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        tenantNoteRepository.save(po);
    }

    /**
     * 在 {@code @CrossTenant} 作用域内写入当前租户 note（注解不阻断写，写门禁照常注入/校验）。
     *
     * @param id      note id
     * @param content note 内容
     */
    @CrossTenant
    void saveCurrentTenantNote(Long id, String content) {
        final LocalDateTime now = LocalDateTime.now();

        final TestTenantNotePO po = new TestTenantNotePO();
        po.setId(id);
        po.setContent(content);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        tenantNoteRepository.save(po);
    }

    /**
     * 在 {@code @CrossTenant} 作用域内再进入提权作用域执行读操作（注解 × 提权组合场景）。
     *
     * @return 所有 note 数量
     */
    @CrossTenant
    long countAllNotesWithElevation() {
        return elevate(tenantNoteRepository::count);
    }

    /**
     * 在 {@code @CrossTenant} 作用域内再进入提权作用域写入显式异租户 note（注解 × 提权组合：写放行）。
     *
     * @param tenantID 目标 tenantID
     * @param id       note id
     * @param content  note 内容
     */
    @CrossTenant
    void saveForeignTenantNoteWithElevation(String tenantID, Long id, String content) {
        final LocalDateTime now = LocalDateTime.now();

        TenantPrivilege.elevated(() -> {
            final TestTenantNotePO po = new TestTenantNotePO();
            po.setId(id);
            po.setTenantID(tenantID);
            po.setContent(content);
            po.setCreateTime(now);
            po.setUpdateTime(now);
            tenantNoteRepository.save(po);
        });
    }

    /**
     * 嵌套 {@code @CrossTenant} 场景：外层注解方法内调用内层注解方法（经代理，AOP 生效）。
     *
     * @return 内层方法返回的 note 列表
     */
    @CrossTenant
    List<TestTenantNotePO> listAllNotesNested() {
        return innerCrossTenantService.listAllNotesInner();
    }

    /**
     * 在 {@code @CrossTenant} 作用域内删除指定租户实体——注解≠提权，带实体删除受写门禁判定：
     * 非提权 + 异租户实体 → 门禁拒绝；本租户实体 → 放行（注解仅撤销读过滤，不撤销写门禁）。
     *
     * @param po 待删除的 tenant-scoped 实体
     */
    @CrossTenant
    void deleteNote(TestTenantNotePO po) {
        tenantNoteRepository.delete(po);
    }
}
