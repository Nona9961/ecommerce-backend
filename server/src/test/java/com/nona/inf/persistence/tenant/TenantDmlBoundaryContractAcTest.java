package com.nona.inf.persistence.tenant;

import com.nona.EcommerceApplication;
import com.nona.annotation.ScaffoldGenerated;
import com.nona.exceptions.BusinessException;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约测试：写门禁边界行为（两条件判定 × 写操作形态分类）：
 * <ul>
 *   <li>D1（PO 形态 → 门禁）：非提权 {@code deleteAllInBatch(集合)} 传异租户 PO → 门禁先拒（实验 D 转正）</li>
 *   <li>D2（ID/无参形态 → filter）：非提权无参 {@code deleteAllInBatch()} 仅删本租户行（bulk filter 契约）</li>
 *   <li>E（归属不可变）：managed 实体改 tenantID + flush 后库中仍为原值</li>
 *   <li>F（红线实证，{@code @Disabled}）：注解内读异租户实体改业务字段 + flush 会落库越权写——
 *       访问点外操作，R5 文档定责（prd），代码保留作证据，勿启用</li>
 * </ul>
 * 两类防线：PO 形态 → 门禁判定；ID/无参形态 → filter 兜底。
 * <p>
 * 上下文制造形态：租户身份经 {@link TrackingContext#withScope} + holder 写入
 * （单级解析主通路），不再依赖请求作用域 bean。
 *
 * @author nona9961
 */
@SpringBootTest(classes = EcommerceApplication.class)
@ScaffoldGenerated
class TenantDmlBoundaryContractAcTest {

    @Autowired
    private TestTenantNoteRepository tenantNoteRepository;

    @Autowired
    private ElevatedTenantTestService elevatedTenantTestService;

    @Autowired
    private TenantPrivilege tenantPrivilege;

    @Autowired
    private CrossTenantTestService crossTenantTestService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        elevatedTenantTestService.deleteAllNotes();
    }

    /**
     * 提权调用包装（受检异常收拢）。
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

    private static TestTenantNotePO newNote(Long id, String tenantID, String content) {
        final LocalDateTime now = LocalDateTime.now();
        final TestTenantNotePO po = new TestTenantNotePO();
        po.setId(id);
        po.setTenantID(tenantID);
        po.setContent(content);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        return po;
    }

    /**
     * 契约 D1（PO 形态 → 门禁先拒）：非提权 {@code deleteAllInBatch(集合)} 传异租户 PO → 参数判定先行拒绝
     * （写操作形态分类：PO 形态受两条件判定），bulk DML 不可达。
     * 原始实验 D 输入即此形态——参数判定先行拦截，filter 兜底不被此形态触达。
     */
    @Test
    void contractD1_poFormDeleteAllInBatchWithForeignTenantShouldBeRejectedByGate() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-A", 401L, "note-a");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 411L, "note-b");
            assertThat(elevatedTenantTestService.listAllNotes()).hasSize(2);

            // 非提权，构造 tenant-B 的 PO 传 deleteAllInBatch（集合形态）→ 门禁先拒
            assertThatThrownBy(() ->
                    tenantNoteRepository.deleteAllInBatch(List.of(newNote(411L, "tenant-B", "note-b"))))
                    .isInstanceOf(BusinessException.class);

            // 门禁先拒：两行数据均未受影响
            assertThat(elevatedTenantTestService.listAllNotes()).hasSize(2);
        });
    }

    /**
     * 契约 D2（ID/无参形态 → filter 兜底）：非提权无参 {@code deleteAllInBatch()} 无实体参数可判定
     * → 写目标合法性由 Hibernate filter 在目标解析阶段达成（bulk 契约）：tenant-A 视野下仅删本租户行，
     * tenant-B 行仍在。filter 对 bulk DML 生效是 Hibernate 行为契约（升级回归清单条目）。
     */
    @Test
    void contractD2_noArgDeleteAllInBatchShouldBeFilteredToCurrentTenant() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-A", 602L, "note-a");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 612L, "note-b");

            tenantNoteRepository.deleteAllInBatch();

            // tenant-A 行被删、tenant-B 行仍在（filter 兜底契约）
            assertThat(elevatedTenantTestService.listAllNotes())
                    .extracting(TestTenantNotePO::getId)
                    .containsExactly(612L);
        });
    }

    /**
     * 契约 E（归属不可变）：managed 实体修改 tenantID + flush 后库中仍为原值。
     * {@code @TenantId} 列不可经实体修改——归属是数据固有属性（JPA 天然支持），写门禁亦不提供归属变更通道。
     */
    @Test
    void contractE_mutateTenantIdOnManagedEntityShouldNotChangeOwnership() throws Exception {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 421L, "note-b");

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // 找到 421 的真实归属：非提权 findById 应被 filter 挡（应为空），提权内可加载
                assertThat(tenantNoteRepository.findById(421L)).isEmpty();

                tenantPrivilege.elevated(() -> {
                    TestTenantNotePO foreign = tenantNoteRepository.findById(421L).orElseThrow();
                    foreign.setTenantID("tenant-A");   // 尝试改写归属（同事务内按需修改）
                    entityManager.flush();             // flush 落下 UPDATE
                });
            });

            // 用提权视角验证 421 行的真实租户归属：库中必须仍为 tenant-B（归属不可变）
            TestTenantNotePO reloaded = elevate(() ->
                    tenantNoteRepository.findById(421L).orElse(null));
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getTenantID())
                    .as("TENANT-MUTATION UNPROTECTED: flush updated tenant_id column of foreign-tenant row")
                    .isEqualTo("tenant-B");
        });
    }

    /**
     * 红线实证（评测期复现，勿启用）：{@code @CrossTenant} 注解读放行内 load 异租户托管实体 → 修改业务字段
     * → flush → 越权写落库（访问点不唯一的实证）。R5 文档定责（prd）：注解内读到的实体仅供读取；
     * 作用域退出 auto-flush 也会落库挂起写（含注解内被改实体），勿依赖「不显式 flush 就不落库」。
     * 代码保留作证据；启用前须先落实访问点外操作防线。
     */
    @Test
    @Disabled("红线实证（评测期复现）：作用域退出 auto-flush 会落库挂起写，R5 文档定责（prd）、勿启用")
    void contractF_annotatedReadThenMutateBusinessFieldThenFlush() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 431L, "original-b");

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // 注解内 load tenant-B 实体（托管状态）——注解放行读
                TestTenantNotePO foreign = crossTenantTestService.getNote(431L);
                assertThat(foreign).isNotNull();
                assertThat(foreign.getTenantID()).isEqualTo("tenant-B");

                // 修改业务字段（托管实体脏检查）——注解只授权读，修改是越权写
                foreign.setContent("HACKED-BY-ANNOTATED-READ");
                entityManager.flush();   // 模拟作用域退出 auto-flush 时点
            });

            // 提权视角读回 431 行真实内容（红线实证：内容已被越权改写）
            TestTenantNotePO reloaded = elevate(() -> tenantNoteRepository.findById(431L).orElse(null));
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getContent())
                    .as("ANNOTATED-READ-MUTATE-FLUSH UNPROTECTED: business field of foreign-tenant row updated")
                    .isEqualTo("original-b");
        });
    }
}
