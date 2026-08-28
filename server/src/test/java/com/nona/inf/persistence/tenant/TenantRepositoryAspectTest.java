package com.nona.inf.persistence.tenant;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.EcommerceApplication;
import com.nona.exceptions.BusinessException;
import com.nona.inf.context.TenantContextAccessor;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.repository.jpa.TestGlobalNoteRepository;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 多租户隔离集成测试：写门禁、读过滤、提权作用域与 filter 双保险。
 *
 * @author nona9961
 */
@SpringBootTest(classes = EcommerceApplication.class)
@ScaffoldGenerated
class TenantRepositoryAspectTest {

    @Autowired
    private TestTenantNoteRepository tenantNoteRepository;

    @Autowired
    private TestGlobalNoteRepository globalNoteRepository;

    @Autowired
    private ThreadContext threadContext;

    @Autowired
    private ElevatedTenantTestService elevatedTenantTestService;

    @Autowired
    private CrossTenantTestService crossTenantTestService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    /**
     * 初始化 request scope（模拟 Web 请求）并清理测试数据。
     */
    @BeforeEach
    void setUpRequestScope() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        elevatedTenantTestService.deleteAllNotes();
    }

    /**
     * 清理测试现场，避免用例互相污染。
     */
    @AfterEach
    void tearDown() {
        threadContext.setTenantID(null);
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 验证 tenant-scoped 的 findAll 在不同 tenant 下隔离；tenant 缺失/空白时 fail-closed 返回空。
     */
    @Test
    void tenantScopedQueryShouldBeFilteredAndFailClosedWhenTenantMissing() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(1L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);
        assertThat(t1.getTenantID()).isEqualTo("t1");

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(2L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);
        assertThat(t2.getTenantID()).isEqualTo("t2");

        threadContext.setTenantID("t1");
        List<TestTenantNotePO> visibleToT1 = tenantNoteRepository.findAll();
        assertThat(visibleToT1).hasSize(1);
        assertThat(visibleToT1.get(0).getId()).isEqualTo(1L);

        threadContext.setTenantID(null);
        assertThat(tenantNoteRepository.findAll()).isEmpty();

        threadContext.setTenantID("   ");
        assertThat(tenantNoteRepository.findAll()).isEmpty();
    }

    /**
     * 验证 tenant-scoped 的 findById 在不同 tenant 下隔离；tenant 缺失/空白时 fail-closed。
     */
    @Test
    void tenantScopedFindByIdShouldBeFiltered() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(1L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(2L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);

        threadContext.setTenantID("t1");
        assertThat(tenantNoteRepository.findById(2L)).isEmpty();

        threadContext.setTenantID("t2");
        assertThat(tenantNoteRepository.findById(2L)).isPresent();

        threadContext.setTenantID(null);
        assertThat(tenantNoteRepository.findById(1L)).isEmpty();

        threadContext.setTenantID("   ");
        assertThat(tenantNoteRepository.findById(1L)).isEmpty();
    }

    /**
     * 验证 tenant-scoped 的 count/existsById 在不同 tenant 下隔离；提权作用域下可跨租户统计。
     */
    @Test
    void tenantScopedCountAndExistsShouldBeFiltered() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(31L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(32L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);

        threadContext.setTenantID("t1");
        assertThat(tenantNoteRepository.count()).isEqualTo(1);
        assertThat(tenantNoteRepository.existsById(31L)).isTrue();
        assertThat(tenantNoteRepository.existsById(32L)).isFalse();

        threadContext.setTenantID("t2");
        assertThat(tenantNoteRepository.count()).isEqualTo(1);
        assertThat(tenantNoteRepository.existsById(32L)).isTrue();

        threadContext.setTenantID(null);
        assertThat(tenantNoteRepository.count()).isZero();
        assertThat(tenantNoteRepository.existsById(31L)).isFalse();

        assertThat(elevatedTenantTestService.countAllNotes()).isEqualTo(2);
        assertThat(elevatedTenantTestService.noteExists(31L)).isTrue();
        assertThat(elevatedTenantTestService.noteExists(32L)).isTrue();

        assertThat(tenantNoteRepository.count()).isZero();
    }

    /**
     * 验证 global entity 不受 tenant filter 影响（tenant 缺失/空白仍可查询）。
     */
    @Test
    void globalQueryShouldNotBeFilteredWhenTenantMissing() {
        LocalDateTime now = LocalDateTime.now();

        TestGlobalNotePO global = new TestGlobalNotePO();
        global.setId(10L);
        global.setContent("global");
        global.setCreateTime(now);
        global.setUpdateTime(now);
        globalNoteRepository.save(global);

        threadContext.setTenantID(null);
        assertThat(globalNoteRepository.findAll()).hasSize(1);

        threadContext.setTenantID("   ");
        assertThat(globalNoteRepository.findAll()).hasSize(1);
    }

    /**
     * 验证 tenant 缺失时 tenant-scoped 写入拒绝。
     */
    @Test
    void tenantScopedWriteShouldFailWhenTenantMissing() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID(null);
        TestTenantNotePO po = new TestTenantNotePO();
        po.setId(3L);
        po.setContent("illegal");
        po.setCreateTime(now);
        po.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> tenantNoteRepository.save(po));
    }

    /**
     * 验证 tenant 空白时 tenant-scoped 写入拒绝。
     */
    @Test
    void tenantScopedWriteShouldFailWhenTenantBlank() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID(" ");
        TestTenantNotePO po = new TestTenantNotePO();
        po.setId(41L);
        po.setContent("illegal");
        po.setCreateTime(now);
        po.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> tenantNoteRepository.save(po));
    }

    /**
     * 验证当前 tenant 与 entity tenant 不一致时写入拒绝。
     */
    @Test
    void tenantScopedWriteShouldRejectMismatchedTenant() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO po = new TestTenantNotePO();
        po.setId(4L);
        po.setTenantID("t2");
        po.setContent("illegal");
        po.setCreateTime(now);
        po.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> tenantNoteRepository.save(po));
    }

    /**
     * 验证 saveAll 会为 tenant-scoped 实体注入 tenantID（实体 tenant 为空/空白）。
     */
    @Test
    void tenantScopedSaveAllShouldInjectTenantID() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");

        TestTenantNotePO po1 = new TestTenantNotePO();
        po1.setId(51L);
        po1.setContent("note-1");
        po1.setCreateTime(now);
        po1.setUpdateTime(now);

        TestTenantNotePO po2 = new TestTenantNotePO();
        po2.setId(52L);
        po2.setTenantID("   ");
        po2.setContent("note-2");
        po2.setCreateTime(now);
        po2.setUpdateTime(now);

        tenantNoteRepository.saveAll(List.of(po1, po2));

        assertThat(po1.getTenantID()).isEqualTo("t1");
        assertThat(po2.getTenantID()).isEqualTo("t1");
        assertThat(tenantNoteRepository.count()).isEqualTo(2);
    }

    /**
     * 验证 saveAll 中存在 tenant 不一致实体时整体拒绝。
     */
    @Test
    void tenantScopedSaveAllShouldRejectMismatchedTenant() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");

        TestTenantNotePO po1 = new TestTenantNotePO();
        po1.setId(61L);
        po1.setContent("note-1");
        po1.setCreateTime(now);
        po1.setUpdateTime(now);

        TestTenantNotePO po2 = new TestTenantNotePO();
        po2.setId(62L);
        po2.setTenantID("t2");
        po2.setContent("note-2");
        po2.setCreateTime(now);
        po2.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> tenantNoteRepository.saveAll(List.of(po1, po2)));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证提权作用域可绕过 tenant 读隔离，且作用域必须收敛（离开作用域后恢复）。
     */
    @Test
    void crossTenantShouldBypassTenantIsolationInReadAndBeScopeBound() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(11L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(12L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);

        threadContext.setTenantID(null);
        assertThat(tenantNoteRepository.findAll()).isEmpty();

        List<TestTenantNotePO> all = elevatedTenantTestService.listAllNotes();
        assertThat(all).hasSize(2);

        assertThat(tenantNoteRepository.findAll()).isEmpty();
    }

    /**
     * 验证提权作用域内 listAllNotes 全量可见（跨 t1/t2 数据均能查到），出作用域后立即恢复隔离。
     */
    @Test
    void elevatedScopeShouldExposeAllTenantsInsideAndRestoreIsolationAfterExit() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(91L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(92L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);

        assertThat(tenantNoteRepository.findAll()).hasSize(1);

        List<TestTenantNotePO> all = elevatedTenantTestService.listAllNotes();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(TestTenantNotePO::getId).containsExactlyInAnyOrder(91L, 92L);

        assertThat(tenantNoteRepository.findAll()).hasSize(1);
        assertThat(tenantNoteRepository.findAll().get(0).getId()).isEqualTo(92L);
    }

    /**
     * 验证提权作用域下 findById 可跨租户读取。
     */
    @Test
    void crossTenantFindByIdShouldBypassIsolation() {
        LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(21L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID(null);
        assertThat(tenantNoteRepository.findById(21L)).isEmpty();
        assertThat(elevatedTenantTestService.getNote(21L)).isNotNull();
    }

    /**
     * 验证提权作用域允许跨租户写（显式提供 entity tenantID，保留原值）。
     */
    @Test
    void crossTenantWriteShouldKeepExplicitTenantID() {
        threadContext.setTenantID("t1");
        elevatedTenantTestService.saveNoteForTenant("t2", 100L, "note-t2");

        threadContext.setTenantID("t2");
        assertThat(tenantNoteRepository.findAll()).hasSize(1);
    }

    /**
     * 验证提权作用域 + entity tenantID 缺失 → 拒绝（修订语义：归属不可发明，fail-closed）。
     * <p>
     * 旧语义为「注入当前 tenant」（admin 普通创建场景）；后经架构审查判定为契约级错误：
     * 「谁做的」是 identity 职责，非租户职责——提权 + 空归属写必须显式报错，而非发明归属。
     * 对应 common 单测 {@code TenantWriteGateTest#elevatedWithNullTenantShouldReject}。
     */
    @Test
    void elevatedWriteWithNullTenantShouldFail() {
        threadContext.setTenantID("t1");

        assertThrows(BusinessException.class,
                () -> elevatedTenantTestService.saveNoteWithoutTenantID(71L, "note-from-admin"));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证提权作用域 + 当前 tenant 缺失 + 实体携带 MISSING 占位 → 写入拒绝（fail-closed）。
     */
    @Test
    void crossTenantWriteShouldRejectMissingPlaceholderEntityTenant() {
        threadContext.setTenantID(null);
        assertThrows(BusinessException.class,
                () -> elevatedTenantTestService.saveNoteForTenant(TenantContextAccessor.MISSING_TENANT_ID, 81L, "illegal"));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证提权作用域 + 当前 tenant 缺失 + 实体显式合法 tenant → 放行且保留原值（C 端跨店下单场景）。
     */
    @Test
    void crossTenantWriteShouldKeepExplicitTenantWhenContextTenantMissing() {
        threadContext.setTenantID(null);
        elevatedTenantTestService.saveNoteForTenant("t1", 100L, "explicit");

        List<TestTenantNotePO> all = elevatedTenantTestService.listAllNotes();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTenantID()).isEqualTo("t1");
        assertThat(all.get(0).getContent()).isEqualTo("explicit");
    }

    /**
     * 验证提权作用域 + 当前 tenant 缺失 + 实体未携带 tenant → 拒绝（双缺失 fail-closed 回归保护）。
     */
    @Test
    void crossTenantWriteShouldRejectWhenBothMissing() {
        threadContext.setTenantID(null);

        assertThrows(BusinessException.class, () -> elevatedTenantTestService.saveNoteWithoutTenantID(82L, "illegal"));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * M2 红点契约（读路径 filter 双保险第二重）：同一事务/session 内“先以 tenant-A 定型，后提权查询”必须全量可见。
     * <p>
     * 场景：TransactionTemplate 回调使所有 repository 调用经 REQUIRED 传播复用同一事务/同一 Hibernate Session；
     * resolver 仅在 session 打开时调用一次并定型租户模式（validateExistingCurrentSessions=false 不做动态校验）。
     * 之后进入提权作用域，期望提权查询绕过单租户过滤看到全部数据。
     * 当前实现只有第一重保险（resolver 在 session 打开时判定 root），缺第二重（提权时切换 Hibernate _tenantId filter）
     * → 提权查询静默仍被过滤 → 本用例在第 4 步红。
     * <p>
     * 前提（session 复用成立，否则本用例不构成 M2 复现）：回调首尾两次 unwrap 得到同一 Session 实例（断言见下）。
     * 数据准备必须发生在独立事务中（session 打开时提权已激活，写入校验放行）——
     * 若在回调内（session 已定型 tenant-A）写入 tenant-B，Hibernate @TenantId 的 flush 校验会直接拒绝（见另一用例），
     * 无法到达本用例要钉的查询红点。
     */
    @Test
    void sameTransactionElevatedScopeShouldExposeAllTenantsAndRestoreAfterExit() {
        threadContext.setTenantID("tenant-A");
        elevatedTenantTestService.saveNoteForTenant("tenant-A", 101L, "note-A");
        elevatedTenantTestService.saveNoteForTenant("tenant-B", 102L, "note-B");
        assertThat(elevatedTenantTestService.listAllNotes()).hasSize(2);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            final Session sessionBefore = entityManager.unwrap(Session.class);
            assertThat(TenantPrivilege.isActive()).isFalse();

            assertThat(tenantNoteRepository.count()).isEqualTo(1);

            TenantPrivilege.elevated(() -> {
                assertThat(tenantNoteRepository.count())
                        .as("elevated query within already-stabilized session should bypass tenant filter")
                        .isEqualTo(2);
            });

            assertThat(tenantNoteRepository.count()).isEqualTo(1);

            final Session sessionAfter = entityManager.unwrap(Session.class);
            assertThat(sessionAfter).isSameAs(sessionBefore);
        });
    }

    /**
     * 验证 {@code @CrossTenant} 注解方法内读全租户可见（无事务/新 session 场景：resolver 自查第一重保险），
     * 出方法后立即恢复隔离。
     */
    @Test
    void crossTenantAnnotatedReadShouldExposeAllTenantsAndRestoreIsolation() {
        final LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(301L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(302L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);

        threadContext.setTenantID("t1");
        assertThat(tenantNoteRepository.findAll()).hasSize(1);

        List<TestTenantNotePO> all = crossTenantTestService.listAllNotes();
        assertThat(all).hasSize(2);

        assertThat(tenantNoteRepository.findAll()).hasSize(1);
    }

    /**
     * 验证 {@code @CrossTenant} 在已定型 session 内生效（同事务场景：数据访问点自查第二重保险，M2 契约同构）
     * 且退出后恢复。
     */
    @Test
    void crossTenantAnnotatedReadShouldBypassInStabilizedSession() {
        threadContext.setTenantID("tenant-A");
        elevatedTenantTestService.saveNoteForTenant("tenant-A", 311L, "note-A");
        elevatedTenantTestService.saveNoteForTenant("tenant-B", 312L, "note-B");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(tenantNoteRepository.count()).isEqualTo(1);

            assertThat(crossTenantTestService.getNote(311L)).isNotNull();
            assertThat(crossTenantTestService.getNote(312L)).isNotNull();

            assertThat(tenantNoteRepository.count()).isEqualTo(1);
        });
    }

    /**
     * 验证嵌套 {@code @CrossTenant}（外层注解内调用内层注解方法）安全：内层退出不影响外层，
     * 全部退出后恢复隔离。
     */
    @Test
    void crossTenantNestedAnnotatedScopesAreSafe() {
        final LocalDateTime now = LocalDateTime.now();

        threadContext.setTenantID("t1");
        TestTenantNotePO t1 = new TestTenantNotePO();
        t1.setId(321L);
        t1.setContent("note-t1");
        t1.setCreateTime(now);
        t1.setUpdateTime(now);
        tenantNoteRepository.save(t1);

        threadContext.setTenantID("t2");
        TestTenantNotePO t2 = new TestTenantNotePO();
        t2.setId(322L);
        t2.setContent("note-t2");
        t2.setCreateTime(now);
        t2.setUpdateTime(now);
        tenantNoteRepository.save(t2);

        threadContext.setTenantID("t1");
        assertThat(crossTenantTestService.listAllNotesNested()).hasSize(2);
        assertThat(tenantNoteRepository.findAll()).hasSize(1);
    }

    /**
     * AC 钉住：注解开启读放行时，写门禁仍拒绝未提权异租户写（注解只影响读）。
     */
    @Test
    void crossTenantAnnotatedWriteShouldStillRequireElevation() {
        threadContext.setTenantID("t1");

        assertThatThrownBy(() -> crossTenantTestService.saveForeignTenantNote("t2", 331L, "illegal"))
                .isInstanceOf(BusinessException.class);
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证注解不阻断写：注解方法内写当前租户实体照常注入/落库。
     */
    @Test
    void crossTenantAnnotatedWriteShouldAllowCurrentTenant() {
        threadContext.setTenantID("t1");

        crossTenantTestService.saveCurrentTenantNote(341L, "note-current");

        threadContext.setTenantID("t1");
        assertThat(tenantNoteRepository.findAll()).hasSize(1);
        assertThat(tenantNoteRepository.findAll().get(0).getTenantID()).isEqualTo("t1");
    }

    /**
     * 验证注解 × 提权组合：注解读放行内再提权，读全租户仍成立；显式异租户写经提权放行。
     */
    @Test
    void elevationInsideAnnotatedMethodShouldAllowForeignWrite() {
        threadContext.setTenantID("t1");

        crossTenantTestService.saveForeignTenantNoteWithElevation("t2", 351L, "elevated-note");

        assertThat(elevatedTenantTestService.countAllNotes()).isEqualTo(1);
        assertThat(elevatedTenantTestService.getNote(351L).getTenantID()).isEqualTo("t2");

        threadContext.setTenantID("t1");
        assertThat(tenantNoteRepository.count()).isZero();
        assertThat(crossTenantTestService.countAllNotesWithElevation()).isEqualTo(1);
    }

    /**
     * 写入面契约（设计意图）：已定型 session 内提权写入显式异租户必须 fail-fast。
     * <p>
     * Hibernate {@code @TenantId} 的 assigned-id 校验（TenantIdGeneration）基于 session 定型租户，
     * 与提权状态无关：session 打开时定型 tenant-A，随后提权写入 tenant-B → flush 时抛
     * PropertyValueException，事务回滚、库中无该数据。
     * 因此提权必须罩住事务边界（正确用法是 elevatedInTransaction，session 打开时提权即定型），
     * 使 session 打开时租户模式即按提权定型，显式异租户写入才会被 Hibernate 放行。
     */
    @Test
    void sameTransactionElevatedWriteShouldFailFastWhenSessionAlreadyStabilizedAreas() {
        final LocalDateTime now = LocalDateTime.now();
        threadContext.setTenantID("tenant-A");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.unwrap(Session.class);
            assertThat(TenantPrivilege.isActive()).isFalse();

            TenantPrivilege.elevated(() -> {
                final TestTenantNotePO po = new TestTenantNotePO();
                po.setId(202L);
                po.setTenantID("tenant-B");
                po.setContent("note-B");
                po.setCreateTime(now);
                po.setUpdateTime(now);
                tenantNoteRepository.save(po);
                // 显式 flush：让 @TenantId assigned-id 校验立刻执行（而非推迟到后续查询的 auto-flush）
                entityManager.flush();
            });
        })).isInstanceOf(RuntimeException.class);

        assertThat(elevatedTenantTestService.listAllNotes())
                .extracting(TestTenantNotePO::getId)
                .doesNotContain(202L);
    }

    /**
     * 同事务保写契约（验收钉住点）：同事务内「读放行作用域写当前租户 → 作用域退出 flush+clear →
     * 事务提交落库」——验证 design D4 顺序承诺「flush 先行防 clear 丢挂起写」。
     * <p>
     * TransactionTemplate 开外层事务（EM 绑定）；事务内 {@code withReadBypass} 作用域写当前租户实体
     * （挂起态）；作用域退出时 EM 仍绑定 → {@code JpaTenantScopeExitHandler} 真实执行
     * {@code flush()+clear()}：挂起写先落库（不丢），再失效缓存；随后事务提交，数据必须仍在库。
     * 若实现退化（无 flush 或 clear 先行），挂起写被 clear 丢弃 → 提交后查无此数据 → 本用例红。
     * <p>
     * 与现存「注解内写」用例（{@code crossTenantAnnotatedWriteShouldAllowCurrentTenant}）的区别：
     * 后者无外层事务（作用域退出时 EM 已解绑，handler 空转），本用例是「flush 保写」语义的唯一真实钉住点。
     */
    @Test
    void sameTransactionBypassWriteShouldSurviveScopeExitFlushClearAndCommit() {
        threadContext.setTenantID("tenant-A");
        final LocalDateTime now = LocalDateTime.now();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            TenantPrivilege.withReadBypass(() -> {
                final TestTenantNotePO po = new TestTenantNotePO();
                po.setId(601L);
                po.setContent("note-current");
                po.setCreateTime(now);
                po.setUpdateTime(now);
                tenantNoteRepository.save(po);   // 非提权 + 当前租户 → 门禁注入 tenant-A，实体挂起
            });
            // 作用域退出：handler 此刻执行 flush+clear（EM 绑定，真实路径）
        });

        // 事务已提交：数据必须落库（flush 先行防 clear 丢挂起写）
        assertThat(tenantNoteRepository.findById(601L)).isPresent();
        assertThat(tenantNoteRepository.findById(601L).orElseThrow().getTenantID()).isEqualTo("tenant-A");
    }

    // ==================== 删除门禁（参数判定覆盖 delete 系列：PO 形态受两条件判定）====================

    /**
     * 验证非提权 {@code delete(entity)} 删异租户实体 → 拒绝（
     * 写门禁判定不依赖方法名——带实体参数的删除同样受两条件判定）。
     */
    @Test
    void nonElevatedDeleteWithForeignTenantShouldBeRejected() {
        threadContext.setTenantID("tenant-A");
        final LocalDateTime now = LocalDateTime.now();

        final TestTenantNotePO poB = new TestTenantNotePO();
        poB.setId(501L);
        poB.setTenantID("tenant-B");
        poB.setContent("foreign");
        poB.setCreateTime(now);
        poB.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> tenantNoteRepository.delete(poB));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证非提权 {@code deleteAll(集合)} 删异租户实体 → 拒绝（Iterable 内 PO 元素逐实体判定）。
     */
    @Test
    void nonElevatedDeleteAllWithForeignTenantShouldBeRejected() {
        threadContext.setTenantID("tenant-A");
        final LocalDateTime now = LocalDateTime.now();

        final TestTenantNotePO poB = new TestTenantNotePO();
        poB.setId(502L);
        poB.setTenantID("tenant-B");
        poB.setContent("foreign");
        poB.setCreateTime(now);
        poB.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> tenantNoteRepository.deleteAll(List.of(poB)));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证非提权删本租户实体 → 正常（先提权造数据，恢复隔离后 delete 本租户实体）。
     */
    @Test
    void nonElevatedDeleteOwnTenantShouldSucceed() {
        threadContext.setTenantID("tenant-A");
        final LocalDateTime now = LocalDateTime.now();

        final TestTenantNotePO po = new TestTenantNotePO();
        po.setId(503L);
        po.setContent("own");
        po.setCreateTime(now);
        po.setUpdateTime(now);
        tenantNoteRepository.save(po);
        assertThat(elevatedTenantTestService.listAllNotes()).hasSize(1);

        tenantNoteRepository.delete(po);

        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证提权删异租户实体 → 放行（提权为写门禁唯一判断源，显式异租户归属放行，归属不变不注入）。
     * <p>
     * 数据由 {@code ElevatedTenantTestService#saveNoteForTenant} 提权创建；删除在提权作用域内
     * 直接调 repository（服务类不在本次改动范围）。
     */
    @Test
    void elevatedDeleteForeignTenantShouldSucceed() {
        threadContext.setTenantID("tenant-A");
        elevatedTenantTestService.saveNoteForTenant("tenant-B", 504L, "foreign");
        assertThat(elevatedTenantTestService.listAllNotes()).hasSize(1);

        final LocalDateTime now = LocalDateTime.now();
        final TestTenantNotePO poB = new TestTenantNotePO();
        poB.setId(504L);
        poB.setTenantID("tenant-B");
        poB.setContent("foreign");
        poB.setCreateTime(now);
        poB.setUpdateTime(now);

        TenantPrivilege.elevated(() -> tenantNoteRepository.delete(poB));

        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }

    /**
     * 验证注解内带实体删除异租户 → 门禁拒绝（注解≠提权：注解只撤销读过滤，
     * 带实体参数的删除仍受两条件判定）。
     */
    @Test
    void annotatedDeleteWithForeignTenantPoShouldBeRejected() {
        threadContext.setTenantID("tenant-A");
        elevatedTenantTestService.saveNoteForTenant("tenant-B", 505L, "foreign");

        final LocalDateTime now = LocalDateTime.now();
        final TestTenantNotePO poB = new TestTenantNotePO();
        poB.setId(505L);
        poB.setTenantID("tenant-B");
        poB.setContent("foreign");
        poB.setCreateTime(now);
        poB.setUpdateTime(now);

        assertThrows(BusinessException.class, () -> crossTenantTestService.deleteNote(poB));
        assertThat(elevatedTenantTestService.listAllNotes()).hasSize(1);
    }

    /**
     * 验证提权 + 空白归属 → 拒绝（空白归一为空归属后拒绝，见
     * {@code TenantWriteGateTest#elevatedWithBlankTenantShouldReject}）。
     * {@code saveNoteForTenant} 可传入任意字符串，故以 "   " 形态直探门禁。
     */
    @Test
    void elevatedWriteWithBlankTenantShouldFail() {
        threadContext.setTenantID("tenant-A");

        assertThrows(BusinessException.class,
                () -> elevatedTenantTestService.saveNoteForTenant("   ", 506L, "blank"));
        assertThat(elevatedTenantTestService.listAllNotes()).isEmpty();
    }
}
