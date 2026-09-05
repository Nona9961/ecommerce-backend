package com.nona.inf.persistence.tenant;

import com.nona.EcommerceApplication;
import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：作用域退出后一级缓存不滞留异租户实体（缓存与视角一致：作用域退出触发
 * flush+clear——先落库保挂起写，再失效一级缓存）。
 * <p>
 * 契约语义：放行（{@code @CrossTenant} / 提权）作用域内读入的异租户实体进入一级缓存；
 * 作用域退出时 {@code JpaTenantScopeExitHandler} 执行 {@code flush()+clear()}（先落库保挂起写、
 * 再失效缓存）；过滤恢复后同事务内 {@code findById} 必须重新发 SQL、受 filter 约束 → 异租户 id 必须为空。
 * <p>
 * 三变体分别钉住三条放行读路径：A = 注解 findById、B = 注解 findAll、C = 提权读。
 * <p>
 * 上下文制造形态：租户身份经 {@link TrackingContext#withScope} + holder 写入
 * （单级解析主通路），不再依赖请求作用域 bean。
 *
 * @author nona9961
 */
@SpringBootTest(classes = EcommerceApplication.class)
@ScaffoldGenerated
class TenantCacheLeakContractTest {

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

    @BeforeEach
    void setUp() {
        elevatedTenantTestService.deleteAllNotes();
    }

    /**
     * 包装提权调用（收拢受检异常，与现有测试服务同式）。
     */
    private <T> T elevateWrap(Callable<T> action) {
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
     * 变体 A（注解 findById 路径）：放行内按 id 直读异租户实体 → 作用域退出（flush+clear）→
     * 过滤恢复后同事务 findById 同 id 必须为空（契约语义：一级缓存不滞留异租户实体）。
     */
    @Test
    void bypassAnnotatedFindByIdThenRestoredFindByIdShouldBeEmpty() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-A", 201L, "note-a");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 211L, "note-b");

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(tenantNoteRepository.count()).isEqualTo(1);
                assertThat(tenantNoteRepository.findById(211L)).isEmpty();

                // 放行读：拿到 tenant-B 实体（既定行为）→ 实体进入一级缓存
                TestTenantNotePO foreign = crossTenantTestService.getNote(211L);
                assertThat(foreign).isNotNull();
                assertThat(foreign.getTenantID()).isEqualTo("tenant-B");

                // 恢复过滤后 findById：契约 = 为空（命中缓存返回异租户实体即违约）
                Optional<TestTenantNotePO> after = tenantNoteRepository.findById(211L);
                assertThat(after)
                        .as("contract(A): level-1 cache must not retain foreign-tenant entity after scope exit")
                        .isEmpty();
            });
        });
    }

    /**
     * 变体 B（注解 findAll 路径）：放行内全量读入异租户实体 → 作用域退出（flush+clear）→
     * 过滤恢复后 findById 已缓存的异租户 id 必须为空（契约语义：一级缓存不滞留异租户实体）。
     */
    @Test
    void bypassAnnotatedFindAllThenRestoredFindByIdShouldBeEmpty() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-A", 202L, "note-a2");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 212L, "note-b2");

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(tenantNoteRepository.count()).isEqualTo(1);

                // 放行全量读：全租户可见（既定行为）→ 异租户实体进入一级缓存
                List<TestTenantNotePO> all = crossTenantTestService.listAllNotes();
                assertThat(all).hasSize(2);

                // 恢复过滤后 findById 已缓存的异租户 id
                Optional<TestTenantNotePO> after = tenantNoteRepository.findById(212L);
                assertThat(after)
                        .as("contract(B): level-1 cache must not retain foreign-tenant entity after scope exit")
                        .isEmpty();
            });
        });
    }

    /**
     * 变体 C（提权模态）：elevated 作用域放行读（非注解）→ 作用域退出（flush+clear）→
     * 过滤恢复后 findById 已缓存的异租户 id 必须为空（契约语义：一级缓存不滞留异租户实体）。
     */
    @Test
    void elevatedReadThenRestoredFindByIdShouldBeEmpty() throws Exception {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-A", 203L, "note-a3");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 213L, "note-b3");

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(tenantNoteRepository.count()).isEqualTo(1);

                // 提权放行读（作用域内直接走 repository，提权模态同样读入异租户实体）
                List<TestTenantNotePO> all = elevateWrap(
                        () -> tenantNoteRepository.findAll());
                assertThat(all).hasSize(2);

                Optional<TestTenantNotePO> after = tenantNoteRepository.findById(213L);
                assertThat(after)
                        .as("contract(C): level-1 cache must not retain foreign-tenant entity after scope exit")
                        .isEmpty();
            });
        });
    }
}
