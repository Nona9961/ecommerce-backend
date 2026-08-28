package com.nona.inf.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import com.nona.annotation.ScaffoldGenerated;

/**
 * Propagates {@link ThreadContext} (tenantID, role, identity) to async worker threads
 * via a static {@link ThreadLocal} fallback slot on {@link TenantContextAccessor}.
 * <p>
 * <strong>Lifecycle</strong>:
 * <ol>
 *   <li>{@link #decorate(Runnable)} captures a {@link TenantContextAccessor.ContextSnapshot}
 *       from the submitting thread's {@link ThreadContext} (via the accessor)</li>
 *   <li>The snapshot is installed into the worker thread before the task runs and cleared after</li>
 * </ol>
 * <strong>Registration</strong>:
 * Downstream projects manually bind this decorator to their {@code ThreadPoolTaskExecutor}:
 * <pre>{@code
 * executor.setTaskDecorator(new RequestContextPropagatingTaskDecorator(tenantContextAccessor));
 * }</pre>
 * No auto-configuration is provided — each async executor must opt in explicitly.
 *
 * @author nona
 */
@Slf4j
@ScaffoldGenerated
public class RequestContextPropagatingTaskDecorator implements TaskDecorator {

    private final TenantContextAccessor tenantContextAccessor;

    /**
     * Constructs a new decorator that uses the given accessor to snapshot the submitting
     * thread's context.
     *
     * @param tenantContextAccessor the context accessor (expected to be a singleton Spring bean)
     */
    public RequestContextPropagatingTaskDecorator(TenantContextAccessor tenantContextAccessor) {
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Captures the current {@link ThreadContext} into a {@link TenantContextAccessor.ContextSnapshot}
     * on the submitting thread, then restores it via {@link TenantContextAccessor#saveSnapshot}
     * on the worker thread.
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        final TenantContextAccessor.ContextSnapshot snapshot = tenantContextAccessor.captureSnapshot();
        log.debug("Captured context snapshot: tenantID={}, role={}, identity={}",
                snapshot.tenantID(), snapshot.role(), snapshot.identity());
        return () -> {
            TenantContextAccessor.saveSnapshot(snapshot);
            try {
                runnable.run();
            } finally {
                TenantContextAccessor.clearSnapshot();
            }
        };
    }
}
