package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.repository.jpa.TestGlobalNoteRepository;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 测试用跨租户服务：封装提权作用域内的读写操作，供租户机制集成测试复用。
 *
 * @author nona9961
 */
@Service
@ScaffoldGenerated
class ElevatedTenantTestService {

    private final TestTenantNoteRepository tenantNoteRepository;
    private final TestGlobalNoteRepository globalNoteRepository;
    private final TenantPrivilege tenantPrivilege;

    /**
     * 进入提权作用域执行有返回值操作；repository 操作不抛受检异常，此处收拢以免污染调用方签名。
     *
     * @param action 提权操作
     * @param <T> 返回类型
     * @return 操作结果
     */
    private <T> T elevate(Callable<T> action) {
        try {
            return tenantPrivilege.elevated(action);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("unexpected checked exception in elevated action", e);
        }
    }

    /**
     * 构造测试用跨租户服务。
     *
     * @param tenantNoteRepository 租户隔离 note 仓库
     * @param globalNoteRepository 全局 note 仓库
     * @param tenantPrivilege     租户提权 bean（构造注入）
     */
    ElevatedTenantTestService(TestTenantNoteRepository tenantNoteRepository,
                              TestGlobalNoteRepository globalNoteRepository,
                              TenantPrivilege tenantPrivilege) {
        this.tenantNoteRepository = tenantNoteRepository;
        this.globalNoteRepository = globalNoteRepository;
        this.tenantPrivilege = tenantPrivilege;
    }

    /**
     * 在提权作用域内清理所有测试数据。
     */
    void deleteAllNotes() {
        tenantPrivilege.elevated(() -> {
            tenantNoteRepository.deleteAll();
            globalNoteRepository.deleteAll();
        });
    }

    /**
     * 在提权作用域内查询所有 tenant-scoped note。
     *
     * @return 所有 note 列表
     */
    List<TestTenantNotePO> listAllNotes() {
        return elevate(tenantNoteRepository::findAll);
    }

    /**
     * 在提权作用域内统计所有 tenant-scoped note 数量。
     *
     * @return note 总数
     */
    long countAllNotes() {
        return elevate(tenantNoteRepository::count);
    }

    /**
     * 在提权作用域内判断指定 note 是否存在。
     *
     * @param id note id
     * @return 是否存在
     */
    boolean noteExists(Long id) {
        return elevate(() -> tenantNoteRepository.existsById(id));
    }

    /**
     * 在提权作用域内按 id 查询 note。
     *
     * @param id note id
     * @return note；不存在则返回 {@code null}
     */
    TestTenantNotePO getNote(Long id) {
        return elevate(() -> tenantNoteRepository.findById(id).orElse(null));
    }

    /**
     * 在提权作用域内为指定 tenant 写入 note（必须显式提供 entity tenantID）。
     *
     * @param tenantID 目标 tenantID
     * @param id note id
     * @param content note 内容
     */
    void saveNoteForTenant(String tenantID, Long id, String content) {
        tenantPrivilege.elevated(() -> {
            LocalDateTime now = LocalDateTime.now();

            TestTenantNotePO po = new TestTenantNotePO();
            po.setId(id);
            po.setTenantID(tenantID);
            po.setContent(content);
            po.setCreateTime(now);
            po.setUpdateTime(now);
            tenantNoteRepository.save(po);
        });
    }

    /**
     * 在提权作用域内写入 note（不提供 entity tenantID），用于验证写入门禁。
     *
     * @param id note id
     * @param content note 内容
     */
    void saveNoteWithoutTenantID(Long id, String content) {
        tenantPrivilege.elevated(() -> {
            final LocalDateTime now = LocalDateTime.now();

            final TestTenantNotePO po = new TestTenantNotePO();
            po.setId(id);
            po.setContent(content);
            po.setCreateTime(now);
            po.setUpdateTime(now);
            tenantNoteRepository.save(po);
        });
    }
}
