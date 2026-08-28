package com.nona.inf.context;

import com.nona.EcommerceApplication;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import com.nona.annotation.ScaffoldGenerated;

/**
 * Verifies {@link RequestContextPropagatingTaskDecorator} cross-thread propagation
 * and {@link TenantContextAccessor} two-level fallback priority.
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
        TenantContextAccessor.clearSnapshot();
        threadContext.setTenantID(null);
        threadContext.setRole(null);
        threadContext.setIdentity(null);
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * Verifies that the decorator captures tenantID, role, and identity from the submitting
     * thread's {@link ThreadContext} and makes them available to the worker thread via
     * the {@link TenantContextAccessor} ThreadLocal fallback.
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
     * Verifies that when both a request-scoped {@link ThreadContext} and a ThreadLocal
     * fallback are available, the request-scoped value takes priority.
     */
    @Test
    void shouldPreferRequestScopeOverThreadLocalFallback() {
        threadContext.setTenantID("from-request");
        threadContext.setRole(List.of("admin"));
        threadContext.setIdentity("req-user");

        final TenantContextAccessor.ContextSnapshot staleSnapshot = new TenantContextAccessor.ContextSnapshot(
                "from-fallback", List.of("visitor"), "fb-user");
        TenantContextAccessor.saveSnapshot(staleSnapshot);

        try {
            assertThat(tenantContextAccessor.getTenantID()).isEqualTo("from-request");

            final TenantContextAccessor.ContextSnapshot captured = tenantContextAccessor.captureSnapshot();
            assertThat(captured.tenantID()).isEqualTo("from-request");
            assertThat(captured.role()).containsExactly("admin");
            assertThat(captured.identity()).isEqualTo("req-user");
        } finally {
            TenantContextAccessor.clearSnapshot();
        }
    }

    /**
     * Verifies that the ThreadLocal fallback is used when request scope is not active.
     */
    @Test
    void shouldUseThreadLocalFallbackWhenNoRequestScope() {
        RequestContextHolder.resetRequestAttributes();

        final TenantContextAccessor.ContextSnapshot snapshot = new TenantContextAccessor.ContextSnapshot(
                "fallback-tenant", List.of("visitor"), "fb-user-99");
        TenantContextAccessor.saveSnapshot(snapshot);

        try {
            assertThat(tenantContextAccessor.getTenantID()).isEqualTo("fallback-tenant");
        } finally {
            TenantContextAccessor.clearSnapshot();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        }
    }

    /**
     * Verifies that empty/null snapshots are treated as absent.
     */
    @Test
    void emptySnapshotShouldYieldNullTenantID() {
        TenantContextAccessor.saveSnapshot(null);
        assertThat(tenantContextAccessor.getTenantID()).isNull();

        TenantContextAccessor.saveSnapshot(TenantContextAccessor.ContextSnapshot.EMPTY);
        assertThat(tenantContextAccessor.getTenantID()).isNull();

        TenantContextAccessor.saveSnapshot(
                new TenantContextAccessor.ContextSnapshot("  ", null, null));
        assertThat(tenantContextAccessor.getTenantID()).isNull();
    }

    /**
     * Verifies that the ThreadLocal fallback is cleared after decorated task execution.
     * After synchronous execution on the same thread, the snapshot must be gone from the
     * static holder, leaving only the request-scoped value (or null when scope is removed).
     */
    @Test
    void shouldClearSnapshotAfterExecution() {
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
}
