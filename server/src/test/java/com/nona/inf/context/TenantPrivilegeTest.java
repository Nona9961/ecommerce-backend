package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.exceptions.BusinessException;
import com.nona.tenant.TenantScopeExitHandler;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TenantPrivilege} 单测：纯 JUnit5 + AssertJ，不依赖 Spring。
 *
 * @author nona9961
 */
@ScaffoldGenerated
class TenantPrivilegeTest {

    /**
     * 被测对象 fixture：空 handler 列表 + 无访问器（纯单测环境，退出通知 no-op、日志身份 unknown）。
     */
    private final TenantPrivilege tenantPrivilege = new TenantPrivilege(List.of(), null);

    @Test
    void runnableScopeActivatesAndRestores() {
        AtomicBoolean inside = new AtomicBoolean(false);

        tenantPrivilege.elevated(() -> inside.set(tenantPrivilege.isActive()));

        assertThat(inside).isTrue();
        assertThat(tenantPrivilege.isActive()).isFalse();
    }

    @Test
    void callableScopeReturnsValue() throws Exception {
        String result = tenantPrivilege.elevated(() -> {
            assertThat(tenantPrivilege.isActive()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tenantPrivilege.isActive()).isFalse();
    }

    @Test
    void callableExceptionPassesThroughAndUnbinds() {
        assertThatThrownBy(() -> tenantPrivilege.elevated(() -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(tenantPrivilege.isActive()).isFalse();
    }

    @Test
    void nestedScopesRestoreLayerByLayer() throws Exception {
        List<Boolean> observed = new ArrayList<>();

        tenantPrivilege.elevated((Runnable) () -> {
            observed.add(tenantPrivilege.isActive());

            // 块体无 return，确保绑定 Runnable 重载（表达式 lambda 会优先解析到 throws Exception 的 Callable 重载）
            tenantPrivilege.elevated(() -> {
                observed.add(tenantPrivilege.isActive());
            });

            observed.add(tenantPrivilege.isActive());
        });

        observed.add(tenantPrivilege.isActive());

        assertThat(observed).containsExactly(true, true, true, false);
    }

    @Test
    void elevationDoesNotLeakIntoNewThread() throws InterruptedException {
        AtomicBoolean inThread = new AtomicBoolean(false);

        Thread thread = new Thread(() -> inThread.set(tenantPrivilege.isActive()));
        thread.start();
        thread.join(5_000);

        assertThat(thread.isAlive()).isFalse();
        assertThat(inThread).isFalse();
    }

    /**
     * 读放行作用域异常路径契约：作用域内抛出的 {@link RuntimeException} 原样透传（同类型同消息），
     * 且退出后状态解绑恢复（不残留）；与提权版 {@code callableExceptionPassesThroughAndUnbinds} 同构。
     */
    @Test
    void readBypassExceptionPassesThroughAndUnbinds() {
        assertThatThrownBy(() -> tenantPrivilege.withReadBypass((Callable<Void>) () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(tenantPrivilege.isReadBypassActive()).isFalse();
        assertThat(tenantPrivilege.isAnyReadBypassActive()).isFalse();
    }

    /**
     * 读放行作用域受检异常契约：{@code withReadBypass(Callable)} 声明 {@code throws Exception}，
     * 受检异常原样透传（核心层 API 不透传包装——切面层 CrossTenantAspect 的包装是另一层职责）。
     */
    @Test
    void readBypassCheckedExceptionPassesThrough() throws Exception {
        assertThatThrownBy(() -> tenantPrivilege.withReadBypass((Callable<Void>) () -> {
            throw new java.io.IOException("io-boom");
        }))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("io-boom");

        assertThat(tenantPrivilege.isReadBypassActive()).isFalse();
    }

    /**
     * 读放行作用域内正常返回值透传。
     */
    @Test
    void readBypassScopeReturnsValue() throws Exception {
        String result = tenantPrivilege.withReadBypass(() -> {
            assertThat(tenantPrivilege.isReadBypassActive()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tenantPrivilege.isReadBypassActive()).isFalse();
    }

    // ===== 读放行（@CrossTenant）状态：与提权分离，读写粒度独立 =====

    /**
     * 读放行作用域进入/退出状态恢复。
     */
    @Test
    void readBypassScopeActivatesAndRestores() {
        AtomicBoolean inside = new AtomicBoolean(false);

        tenantPrivilege.withReadBypass((Runnable) () -> {
            assertThat(tenantPrivilege.isReadBypassActive()).isTrue();
            assertThat(tenantPrivilege.isAnyReadBypassActive()).isTrue();
            inside.set(true);
        });

        assertThat(inside).isTrue();
        assertThat(tenantPrivilege.isReadBypassActive()).isFalse();
        assertThat(tenantPrivilege.isAnyReadBypassActive()).isFalse();
    }

    @Test
    void readBypassScopeDoesNotActivateWriteElevation() {
        tenantPrivilege.withReadBypass((Runnable) () ->
                assertThat(tenantPrivilege.isActive())
                        .as("read bypass must NOT activate write elevation (read/write separation)")
                        .isFalse());
    }

    /**
     * ScopedValue 不跨线程传播的 fail-fast 语义：主线程处于读放行作用域时启动的新线程内
     * {@link TenantPrivilege#isReadBypassActive()} 必须为 false——读放行状态跟代码位置走，
     * 不随线程漂移（与提权对称，见 {@link #elevationDoesNotLeakIntoNewThread()}）。
     */
    @Test
    void readBypassDoesNotLeakIntoNewThread() throws InterruptedException {
        AtomicBoolean inThread = new AtomicBoolean(false);

        tenantPrivilege.withReadBypass((Runnable) () -> {
            Thread thread = new Thread(() -> inThread.set(tenantPrivilege.isReadBypassActive()));
            thread.start();
            try {
                thread.join(5_000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for read-bypass leak probe", e);
            }

            assertThat(thread.isAlive()).isFalse();
        });

        assertThat(inThread).isFalse();
    }

    @Test
    void elevationAndReadBypassAreIndependentAndCompose() {
        List<Boolean> observed = new ArrayList<>();

        tenantPrivilege.elevated((Runnable) () -> {
            observed.add(tenantPrivilege.isActive());
            observed.add(tenantPrivilege.isAnyReadBypassActive());

            tenantPrivilege.withReadBypass((Runnable) () -> {
                observed.add(tenantPrivilege.isReadBypassActive());
                observed.add(tenantPrivilege.isAnyReadBypassActive());
            });

            observed.add(tenantPrivilege.isAnyReadBypassActive());
        });

        observed.add(tenantPrivilege.isAnyReadBypassActive());

        assertThat(observed).containsExactly(true, true, true, true, true, false);
    }

    // ===== M1 契约：elevatedInTransaction（Mockito mock 事务模板，绕开真实事务管理） =====

    /**
     * 成功路径：action 返回值正确透传；且事务 execute 回调确实在提权绑定内执行
     * （回调内 isActive()==true），出作用域后恢复非提权。
     */
    @Test
    void elevatedInTransactionShouldPassThroughResultAndBindElevationInsideCallback() throws Exception {
        TransactionTemplate transactionTemplate = mockingTransactionTemplate();

        String result = tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
            assertThat(tenantPrivilege.isActive()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tenantPrivilege.isActive()).isFalse();
    }

    /**
     * M1 红点契约：action 抛出 BusinessException（RuntimeException）时必须原样透传
     * （同类型、同消息），以便 ControllerAdvice 捕获并把业务消息返回给前端。
     * 当前实现 {@code catch (Exception e)} 把 RuntimeException 也包装为 IllegalStateException → 本用例红。
     */
    @Test
    void elevatedInTransactionShouldPassThroughBusinessExceptionUnwrapped() {
        TransactionTemplate transactionTemplate = mockingTransactionTemplate();

        assertThatThrownBy(() -> tenantPrivilege.elevatedInTransaction(transactionTemplate,
                () -> {
                    throw new BusinessException("business boom");
                }))
                .isInstanceOf(BusinessException.class)
                .hasMessage("business boom");
    }

    /**
     * 契约锁定：action 抛出受检异常时包装为 IllegalStateException 且 cause 保留原异常
     * （当前实现已满足，防回归）。
     */
    @Test
    void elevatedInTransactionShouldWrapCheckedExceptionWithCause() {
        TransactionTemplate transactionTemplate = mockingTransactionTemplate();

        assertThatThrownBy(() -> tenantPrivilege.elevatedInTransaction(transactionTemplate,
                () -> {
                    throw new Exception("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("elevated transaction failed")
                .hasRootCauseInstanceOf(Exception.class)
                .hasRootCauseMessage("boom");
    }

    /**
     * 多 handler 契约：作用域退出时列表内所有 handler 均被通知——
     * 容器按收集注入完整列表，逐个通知。
     */
    @Test
    void scopeExitNotifiesEveryRegisteredHandler() {
        TenantScopeExitHandler handlerA = mock(TenantScopeExitHandler.class);
        TenantScopeExitHandler handlerB = mock(TenantScopeExitHandler.class);
        TenantPrivilege privilege = new TenantPrivilege(List.of(handlerA, handlerB), null);

        privilege.elevated((Runnable) () -> { });

        verify(handlerA).onScopeExited();
        verify(handlerB).onScopeExited();
    }

    /**
     * 多 handler 失败隔离契约：单个 handler 清理失败（抛 RuntimeException）→ 记录日志不重抛、
     * 不阻断其余 handler、业务异常原样透传。
     */
    @Test
    void scopeExitHandlerFailureDoesNotBlockOthersNorBusinessException() {
        TenantScopeExitHandler failingHandler = mock(TenantScopeExitHandler.class);
        TenantScopeExitHandler healthyHandler = mock(TenantScopeExitHandler.class);
        doThrow(new IllegalStateException("cleanup boom")).when(failingHandler).onScopeExited();
        TenantPrivilege privilege = new TenantPrivilege(List.of(failingHandler, healthyHandler), null);

        assertThatThrownBy(() -> privilege.elevated((Runnable) () -> {
            throw new BusinessException("business boom");
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessage("business boom");

        verify(healthyHandler).onScopeExited();
        verify(failingHandler).onScopeExited();
    }

    /**
     * 空 handler 列表 no-op 契约：纯单测环境（无容器、无 handler）下作用域退出通知为空操作。
     */
    @Test
    void scopeExitWithEmptyHandlersIsNoOp() {
        TenantPrivilege privilege = new TenantPrivilege(List.of(), null);

        privilege.elevated((Runnable) () -> { });
        privilege.withReadBypass((Runnable) () -> { });
        // 无异常即契约成立；状态恢复照常
        assertThat(privilege.isActive()).isFalse();
        assertThat(privilege.isAnyReadBypassActive()).isFalse();
    }

    /**
     * 构造 mock 事务模板：stub execute 为直接调用回调的 doInTransaction，
     * 不模拟真实事务管理（回滚/提交），保留与真实实现一致的异常传播路径。
     */
    private static TransactionTemplate mockingTransactionTemplate() {
        final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
        return transactionTemplate;
    }
}
