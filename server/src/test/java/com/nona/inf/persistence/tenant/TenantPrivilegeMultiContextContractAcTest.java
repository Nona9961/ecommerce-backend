package com.nona.inf.persistence.tenant;

import com.nona.EcommerceApplication;
import com.nona.annotation.ScaffoldGenerated;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.repository.jpa.TestTenantNoteRepository;
import com.nona.tenant.TenantScopeExitHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多 Spring context 并存契约（process-wide 静态注册表形态的对立面）：
 * <p>
 * 两个 {@code @SpringBootTest} context（不同 properties → 不同 context cache key）同时存活于
 * 同一 JVM。每个 context 各自实例化自己的 {@link TenantPrivilege} bean、收集自己的
 * {@code List<TenantScopeExitHandler>}（真实 {@link JpaTenantScopeExitHandler} + 各自登记的
 * 测试 handler）。契约：
 * <ul>
 *   <li>收集隔离：每个 context 的 handler 列表只含本 context 的实现，不含对方 context 的实现</li>
 *   <li>通知隔离：A context 的作用域退出只通知 A 的 handler，B 的 handler 计数不变（反之亦然）</li>
 *   <li>租户缓存契约在第二 context 下同样成立（该 context 自己的 JpaTenantScopeExitHandler 生效）</li>
 * </ul>
 * 两个顶层测试类共享静态观测器（测试专用观测手段，非生产机制）：
 * {@link #HANDLER_CALLS} 按 context 标记记录通知次数，{@link #SEEN_PRIVILEGES} 记录各 context
 * 的 {@link TenantPrivilege} 实例以证明 bean 形态下的 per-context 实例化。
 *
 * @author nona9961
 */
@ScaffoldGenerated
abstract class TenantPrivilegeMultiContextContractAcTest {

    static final ConcurrentMap<String, AtomicInteger> HANDLER_CALLS = new ConcurrentHashMap<>();
    static final ConcurrentMap<String, TenantPrivilege> SEEN_PRIVILEGES = new ConcurrentHashMap<>();

    /**
     * 测试用作用域退出处理器：按 context 标记累计通知次数到共享观测器。
     */
    static final class RecordingScopeExitHandler implements TenantScopeExitHandler {

        private final String contextTag;

        RecordingScopeExitHandler(String contextTag) {
            this.contextTag = contextTag;
        }

        /**
         * context 标记（测试观测用）。
         *
         * @return 标记
         */
        String contextTag() {
            return contextTag;
        }

        @Override
        public void onScopeExited() {
            HANDLER_CALLS.computeIfAbsent(contextTag, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /**
     * 断言共享观测器中仅指定 tag 的 handler 被本次调用触发（增量断言，与执行顺序无关）：
     * 自身计数 = before+1；其它 tag 计数保持 before 不动。
     */
    static void assertOnlyTagInvoked(String tag, Map<String, Integer> before) {
        assertThat(HANDLER_CALLS.getOrDefault(tag, new AtomicInteger()).get())
                .as("own context handler must be notified (tag=%s)", tag)
                .isEqualTo(before.getOrDefault(tag, 0) + 1);
        before.forEach((otherTag, count) -> {
            if (!otherTag.equals(tag)) {
                assertThat(HANDLER_CALLS.getOrDefault(otherTag, new AtomicInteger()).get())
                        .as("handlers of other contexts must NOT be notified (tag=%s)", otherTag)
                        .isEqualTo(count);
            }
        });
    }

    /**
     * 快照当前所有 context 的 handler 通知计数（含尚未来得及注册的 tag，记为 0）。
     */
    static Map<String, Integer> snapshotCalls() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        HANDLER_CALLS.forEach((tag, counter) -> snapshot.put(tag, counter.get()));
        snapshot.putIfAbsent("A", 0);
        snapshot.putIfAbsent("B", 0);
        return snapshot;
    }

    /**
     * 断言已观测的 {@link TenantPrivilege} 实例互不相同（不同 context → 不同 bean 实例）。
     */
    static void assertDistinctPrivilegeInstances(String ownTag, TenantPrivilege own) {
        SEEN_PRIVILEGES.putIfAbsent(ownTag, own);
        SEEN_PRIVILEGES.forEach((tag, privilege) -> {
            if (!tag.equals(ownTag)) {
                assertThat(privilege)
                        .as("TenantPrivilege bean must be per-context (tag=%s vs %s)", tag, ownTag)
                        .isNotSameAs(own);
            }
        });
    }

    private TenantPrivilegeMultiContextContractAcTest() {
        // 工具基类，不实例化
    }
}

/**
 * Context A（probe=A）：验证收集隔离、通知隔离与租户缓存契约。
 */
@SpringBootTest(classes = EcommerceApplication.class, properties = "tenant.multi-context.probe=A")
@Import(TenantPrivilegeMultiContextContractAAcTest.ProbeAConfiguration.class)
@ScaffoldGenerated
class TenantPrivilegeMultiContextContractAAcTest {

    @TestConfiguration
    static class ProbeAConfiguration {

        @Bean
        TenantScopeExitHandler recordingScopeExitHandlerA() {
            return new TenantPrivilegeMultiContextContractAcTest.RecordingScopeExitHandler("A");
        }
    }

    @Autowired
    private TenantPrivilege tenantPrivilege;

    @Autowired
    private List<TenantScopeExitHandler> scopeExitHandlers;

    @Autowired
    private TestTenantNoteRepository tenantNoteRepository;

    @Autowired
    private ElevatedTenantTestService elevatedTenantTestService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void contextCollectsItsOwnHandlersOnly() {
        assertThat(scopeExitHandlers)
                .as("context A must collect its own JpaTenantScopeExitHandler + probe handler")
                .hasSize(2)
                .anyMatch(h -> h instanceof JpaTenantScopeExitHandler)
                .anyMatch(h -> h instanceof TenantPrivilegeMultiContextContractAcTest.RecordingScopeExitHandler r
                        && "A".equals(r.contextTag()));
        assertThat(scopeExitHandlers)
                .as("context A must NOT collect context B's probe handler")
                .noneMatch(h -> h instanceof TenantPrivilegeMultiContextContractAcTest.RecordingScopeExitHandler r
                        && "B".equals(r.contextTag()));
        TenantPrivilegeMultiContextContractAcTest.assertDistinctPrivilegeInstances("A", tenantPrivilege);
    }

    @Test
    void scopeExitNotifiesOnlyOwnContextHandler() {
        Map<String, Integer> before = TenantPrivilegeMultiContextContractAcTest.snapshotCalls();

        tenantPrivilege.elevated((Runnable) () -> { });

        TenantPrivilegeMultiContextContractAcTest.assertOnlyTagInvoked("A", before);
    }

    /**
     * 租户缓存契约在第二 context 下同样成立：租户身份经
     * {@link TrackingContext#withScope} + holder 写入（不依赖请求作用域 bean），
     * 作用域退出 flush+clear 语义由该 context 自己的 JpaTenantScopeExitHandler 驱动。
     */
    @Test
    void tenantCacheContractHoldsInSecondContext() throws Exception {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID("tenant-A");
            elevatedTenantTestService.saveNoteForTenant("tenant-A", 801L, "note-a");
            elevatedTenantTestService.saveNoteForTenant("tenant-B", 811L, "note-b");

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(tenantNoteRepository.count()).isEqualTo(1);

                List<TestTenantNotePO> all;
                try {
                    all = tenantPrivilege.elevated(() -> tenantNoteRepository.findAll());
                }
                catch (Exception e) {
                    throw new IllegalStateException("unexpected checked exception in elevated action", e);
                }
                assertThat(all).hasSize(2);

                Optional<TestTenantNotePO> after =
                        tenantNoteRepository.findById(811L);
                assertThat(after)
                        .as("multi-context contract: L1 cache must not retain foreign-tenant entity after scope exit")
                        .isEmpty();
            });
        });
    }
}

/**
 * Context B（probe=B）：与 A 对称——证明两 context 并存时互不覆盖、互不通知。
 */
@SpringBootTest(classes = EcommerceApplication.class, properties = "tenant.multi-context.probe=B")
@Import(TenantPrivilegeMultiContextContractBAcTest.ProbeBConfiguration.class)
@ScaffoldGenerated
class TenantPrivilegeMultiContextContractBAcTest {

    @TestConfiguration
    static class ProbeBConfiguration {

        @Bean
        TenantScopeExitHandler recordingScopeExitHandlerB() {
            return new TenantPrivilegeMultiContextContractAcTest.RecordingScopeExitHandler("B");
        }
    }

    @Autowired
    private TenantPrivilege tenantPrivilege;

    @Autowired
    private List<TenantScopeExitHandler> scopeExitHandlers;

    @Test
    void contextCollectsItsOwnHandlersOnly() {
        assertThat(scopeExitHandlers)
                .as("context B must collect its own JpaTenantScopeExitHandler + probe handler")
                .hasSize(2)
                .anyMatch(h -> h instanceof JpaTenantScopeExitHandler)
                .anyMatch(h -> h instanceof TenantPrivilegeMultiContextContractAcTest.RecordingScopeExitHandler r
                        && "B".equals(r.contextTag()));
        assertThat(scopeExitHandlers)
                .as("context B must NOT collect context A's probe handler")
                .noneMatch(h -> h instanceof TenantPrivilegeMultiContextContractAcTest.RecordingScopeExitHandler r
                        && "A".equals(r.contextTag()));
        TenantPrivilegeMultiContextContractAcTest.assertDistinctPrivilegeInstances("B", tenantPrivilege);
    }

    @Test
    void scopeExitNotifiesOnlyOwnContextHandler() {
        Map<String, Integer> before = TenantPrivilegeMultiContextContractAcTest.snapshotCalls();

        tenantPrivilege.withReadBypass((Runnable) () -> { });

        TenantPrivilegeMultiContextContractAcTest.assertOnlyTagInvoked("B", before);
    }
}