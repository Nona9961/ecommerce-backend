package com.nona.inf.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nona.EcommerceApplication;
import com.nona.annotation.ScaffoldGenerated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskDecorator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 {@link RequestContextPropagatingTaskDecorator} 跨线程传播
 * 与 {@link TenantContextAccessor} 两级回退优先级（ScopedValue 载体）。
 *
 * @author nona
 */
@SpringBootTest(classes = EcommerceApplication.class)
@ScaffoldGenerated
class RequestContextPropagatingTaskDecoratorTest {

    @Autowired
    private TenantContextAccessor tenantContextAccessor;

    @Autowired
    private ThreadContext threadContext;

    private TaskDecorator taskDecorator;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        taskDecorator = new RequestContextPropagatingTaskDecorator(tenantContextAccessor);
    }

    @AfterEach
    void tearDown() {
        threadContext.setTenantID(null);
        threadContext.setRole(null);
        threadContext.setIdentity(null);
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 验证装饰器从提交线程的 {@link ThreadContext} 捕获 tenantID / role / identity，
     * 并经 {@link TenantContextAccessor} 的 ScopedValue 回退在 worker 线程可见。
     */
    @Test
    void shouldPropagateContextSnapshotToWorkerThread() throws Exception {
        threadContext.setTenantID("tenant-a");
        threadContext.setRole(List.of("admin", "editor"));
        threadContext.setIdentity("user-42");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> capturedTenant = new AtomicReference<>();
        final AtomicReference<List<String>> capturedRole = new AtomicReference<>();
        final AtomicReference<String> capturedIdentity = new AtomicReference<>();

        final Runnable task = () -> {
            capturedTenant.set(tenantContextAccessor.getTenantID());
            capturedRole.set(tenantContextAccessor.getRole());
            capturedIdentity.set(tenantContextAccessor.getIdentity());
            latch.countDown();
        };

        final Runnable decorated = taskDecorator.decorate(task);
        final Thread worker = new Thread(decorated);
        worker.start();
        final boolean completed = latch.await(5, TimeUnit.SECONDS);
        worker.join();

        assertThat(completed).isTrue();
        assertThat(capturedTenant.get()).isEqualTo("tenant-a");
        assertThat(capturedRole.get()).containsExactly("admin", "editor");
        assertThat(capturedIdentity.get()).isEqualTo("user-42");
    }

    /**
     * 验证请求作用域优先于 ScopedValue 回退：两者同时存在时，读取与捕获均取请求作用域值。
     */
    @Test
    void shouldPreferRequestScopeOverScopedValueFallback() {
        threadContext.setTenantID("from-request");
        threadContext.setRole(List.of("admin"));
        threadContext.setIdentity("req-user");

        final TenantContextAccessor.ContextSnapshot staleSnapshot = new TenantContextAccessor.ContextSnapshot(
                "from-fallback", List.of("visitor"), "fb-user");
        TenantContextAccessor.withSnapshot(staleSnapshot, () -> {
            assertThat(tenantContextAccessor.getTenantID()).isEqualTo("from-request");

            final TenantContextAccessor.ContextSnapshot captured = tenantContextAccessor.captureSnapshot();
            assertThat(captured.tenantID()).isEqualTo("from-request");
            assertThat(captured.role()).containsExactly("admin");
            assertThat(captured.identity()).isEqualTo("req-user");
        });
    }

    /**
     * 验证请求作用域缺失时，ScopedValue 回退生效：绑定快照后读取到回退值。
     */
    @Test
    void shouldUseScopedValueFallbackWhenNoRequestScope() {
        RequestContextHolder.resetRequestAttributes();
        try {
            final TenantContextAccessor.ContextSnapshot snapshot = new TenantContextAccessor.ContextSnapshot(
                    "fallback-tenant", List.of("visitor"), "fb-user-99");
            TenantContextAccessor.withSnapshot(snapshot, () -> {
                assertThat(tenantContextAccessor.getTenantID()).isEqualTo("fallback-tenant");
            });
        } finally {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        }
    }

    /**
     * 验证空快照视为无身份（fail-closed）：未绑定 / 绑定 {@link TenantContextAccessor.ContextSnapshot#EMPTY} /
     * 绑定空白 tenantID 快照，读取均返回 null。
     */
    @Test
    void emptySnapshotShouldYieldNullTenantID() {
        RequestContextHolder.resetRequestAttributes();
        try {
            assertThat(tenantContextAccessor.getTenantID()).isNull();

            TenantContextAccessor.withSnapshot(TenantContextAccessor.ContextSnapshot.EMPTY, () -> {
                assertThat(tenantContextAccessor.getTenantID()).isNull();
            });

            TenantContextAccessor.withSnapshot(
                    new TenantContextAccessor.ContextSnapshot("  ", null, null), () -> {
                assertThat(tenantContextAccessor.getTenantID()).isNull();
            });
        } finally {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        }
    }

    /**
     * 验证结构化作用域语义：任务执行期间快照可见，作用域退出（同步执行返回后）
     * 自动恢复 unbound——无需手动清理，线程上无残留。
     */
    @Test
    void shouldAutoRestoreSnapshotAfterScopeExit() {
        threadContext.setTenantID("tenant-x");

        final Runnable task = taskDecorator.decorate(() -> {
            assertThat(tenantContextAccessor.getTenantID()).isEqualTo("tenant-x");
        });

        assertThat(tenantContextAccessor.getTenantID()).isEqualTo("tenant-x");

        task.run();

        RequestContextHolder.resetRequestAttributes();
        try {
            assertThat(tenantContextAccessor.getTenantID()).isNull();
        } finally {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        }
    }

    /**
     * 验证池化线程复用无残留：单线程池先后执行带快照与无快照任务，
     * 第二个任务读不到第一个任务的租户（绑定随作用域退出自动消失）。
     */
    @Test
    void pooledThreadReuseShouldNotLeakSnapshot() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            threadContext.setTenantID("tenant-a");
            pool.submit(taskDecorator.decorate(() -> {
                assertThat(tenantContextAccessor.getTenantID()).isEqualTo("tenant-a");
            })).get();

            threadContext.setTenantID(null);
            final AtomicReference<String> captured = new AtomicReference<>();
            pool.submit(taskDecorator.decorate(() -> captured.set(tenantContextAccessor.getTenantID()))).get();

            assertThat(captured.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证异常路径自动恢复：任务内抛异常后，同一池化线程再执行任务读不到残留快照。
     */
    @Test
    void exceptionPathShouldAutoRestoreBinding() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            threadContext.setTenantID("tenant-a");
            final Future<?> failing = pool.submit(taskDecorator.decorate(() -> {
                throw new IllegalStateException("boom");
            }));
            assertThatThrownBy(failing::get).isInstanceOf(ExecutionException.class);

            threadContext.setTenantID(null);
            final AtomicReference<String> captured = new AtomicReference<>();
            pool.submit(taskDecorator.decorate(() -> captured.set(tenantContextAccessor.getTenantID()))).get();

            assertThat(captured.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证嵌套异步传播：worker 内再派发任务时，captureSnapshot 回退读已绑定快照，
     * 内层 worker 继承外层租户视角（捕获不对称修复）。
     */
    @Test
    void nestedDispatchShouldInheritOuterSnapshot() throws Exception {
        final ExecutorService outer = Executors.newSingleThreadExecutor();
        final ExecutorService inner = Executors.newSingleThreadExecutor();
        try {
            threadContext.setTenantID("tenant-a");
            final AtomicReference<String> nestedCaptured = new AtomicReference<>();
            final AtomicReference<String> innerCaptured = new AtomicReference<>();
            outer.submit(taskDecorator.decorate(() -> {
                nestedCaptured.set(tenantContextAccessor.captureSnapshot().tenantID());
                try {
                    inner.submit(taskDecorator.decorate(
                            () -> innerCaptured.set(tenantContextAccessor.getTenantID()))).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("nested dispatch interrupted", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("nested dispatch failed", e);
                }
            })).get();

            assertThat(nestedCaptured.get()).isEqualTo("tenant-a");
            assertThat(innerCaptured.get()).isEqualTo("tenant-a");
        } finally {
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    /**
     * 验证同线程嵌套绑定不串扰：内层绑定覆盖读取，退出后自动恢复外层绑定；
     * 全部退出后恢复 unbound（动态作用域语义）。
     */
    @Test
    void nestedScopeShouldRestoreOuterBinding() {
        final TenantContextAccessor.ContextSnapshot outerSnapshot = new TenantContextAccessor.ContextSnapshot(
                "outer-tenant", List.of("admin"), "outer-user");
        final TenantContextAccessor.ContextSnapshot innerSnapshot = new TenantContextAccessor.ContextSnapshot(
                "inner-tenant", List.of("visitor"), "inner-user");
        TenantContextAccessor.withSnapshot(outerSnapshot, () -> {
            assertThat(tenantContextAccessor.getTenantID()).isEqualTo("outer-tenant");
            TenantContextAccessor.withSnapshot(innerSnapshot, () -> {
                assertThat(tenantContextAccessor.getTenantID()).isEqualTo("inner-tenant");
            });
            assertThat(tenantContextAccessor.getTenantID()).isEqualTo("outer-tenant");
        });

        assertThat(tenantContextAccessor.getTenantID()).isNull();
    }
}
