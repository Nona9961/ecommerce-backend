package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.exceptions.BusinessException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantPrivilege} 单测：纯 JUnit5 + AssertJ，不依赖 Spring。
 *
 * @author nona9961
 */
@ScaffoldGenerated
class TenantPrivilegeTest {

    @Test
    void runnableScopeActivatesAndRestores() {
        AtomicBoolean inside = new AtomicBoolean(false);

        TenantPrivilege.elevated(() -> inside.set(TenantPrivilege.isActive()));

        assertThat(inside).isTrue();
        assertThat(TenantPrivilege.isActive()).isFalse();
    }

    @Test
    void callableScopeReturnsValue() throws Exception {
        String result = TenantPrivilege.elevated(() -> {
            assertThat(TenantPrivilege.isActive()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(TenantPrivilege.isActive()).isFalse();
    }

    @Test
    void callableExceptionPassesThroughAndUnbinds() {
        assertThatThrownBy(() -> TenantPrivilege.elevated(() -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(TenantPrivilege.isActive()).isFalse();
    }

    @Test
    void nestedScopesRestoreLayerByLayer() throws Exception {
        List<Boolean> observed = new ArrayList<>();

        TenantPrivilege.elevated((Runnable) () -> {
            observed.add(TenantPrivilege.isActive());

            // 块体无 return，确保绑定 Runnable 重载（表达式 lambda 会优先解析到 throws Exception 的 Callable 重载）
            TenantPrivilege.elevated(() -> {
                observed.add(TenantPrivilege.isActive());
            });

            observed.add(TenantPrivilege.isActive());
        });

        observed.add(TenantPrivilege.isActive());

        assertThat(observed).containsExactly(true, true, true, false);
    }

    @Test
    void elevationDoesNotLeakIntoNewThread() throws InterruptedException {
        AtomicBoolean inThread = new AtomicBoolean(false);

        Thread thread = new Thread(() -> inThread.set(TenantPrivilege.isActive()));
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
        assertThatThrownBy(() -> TenantPrivilege.withReadBypass((Callable<Void>) () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(TenantPrivilege.isReadBypassActive()).isFalse();
        assertThat(TenantPrivilege.isAnyReadBypassActive()).isFalse();
    }

    /**
     * 读放行作用域受检异常契约：{@code withReadBypass(Callable)} 声明 {@code throws Exception}，
     * 受检异常原样透传（核心层 API 不透传包装——切面层 CrossTenantAspect 的包装是另一层职责）。
     */
    @Test
    void readBypassCheckedExceptionPassesThrough() throws Exception {
        assertThatThrownBy(() -> TenantPrivilege.withReadBypass((Callable<Void>) () -> {
            throw new java.io.IOException("io-boom");
        }))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("io-boom");

        assertThat(TenantPrivilege.isReadBypassActive()).isFalse();
    }

    /**
     * 读放行作用域内正常返回值透传。
     */
    @Test
    void readBypassScopeReturnsValue() throws Exception {
        String result = TenantPrivilege.withReadBypass(() -> {
            assertThat(TenantPrivilege.isReadBypassActive()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(TenantPrivilege.isReadBypassActive()).isFalse();
    }

    // ===== 读放行（@CrossTenant）状态：与提权分离，读写粒度独立 =====

    /**
     * 读放行作用域进入/退出状态恢复。
     */
    @Test
    void readBypassScopeActivatesAndRestores() {
        AtomicBoolean inside = new AtomicBoolean(false);

        TenantPrivilege.withReadBypass((Runnable) () -> {
            assertThat(TenantPrivilege.isReadBypassActive()).isTrue();
            assertThat(TenantPrivilege.isAnyReadBypassActive()).isTrue();
            inside.set(true);
        });

        assertThat(inside).isTrue();
        assertThat(TenantPrivilege.isReadBypassActive()).isFalse();
        assertThat(TenantPrivilege.isAnyReadBypassActive()).isFalse();
    }

    @Test
    void readBypassScopeDoesNotActivateWriteElevation() {
        TenantPrivilege.withReadBypass((Runnable) () ->
                assertThat(TenantPrivilege.isActive())
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

        TenantPrivilege.withReadBypass((Runnable) () -> {
            Thread thread = new Thread(() -> inThread.set(TenantPrivilege.isReadBypassActive()));
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

        TenantPrivilege.elevated((Runnable) () -> {
            observed.add(TenantPrivilege.isActive());
            observed.add(TenantPrivilege.isAnyReadBypassActive());

            TenantPrivilege.withReadBypass((Runnable) () -> {
                observed.add(TenantPrivilege.isReadBypassActive());
                observed.add(TenantPrivilege.isAnyReadBypassActive());
            });

            observed.add(TenantPrivilege.isAnyReadBypassActive());
        });

        observed.add(TenantPrivilege.isAnyReadBypassActive());

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

        String result = TenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
            assertThat(TenantPrivilege.isActive()).isTrue();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(TenantPrivilege.isActive()).isFalse();
    }

    /**
     * M1 红点契约：action 抛出 BusinessException（RuntimeException）时必须原样透传
     * （同类型、同消息），以便 ControllerAdvice 捕获并把业务消息返回给前端。
     * 当前实现 {@code catch (Exception e)} 把 RuntimeException 也包装为 IllegalStateException → 本用例红。
     */
    @Test
    void elevatedInTransactionShouldPassThroughBusinessExceptionUnwrapped() {
        TransactionTemplate transactionTemplate = mockingTransactionTemplate();

        assertThatThrownBy(() -> TenantPrivilege.elevatedInTransaction(transactionTemplate,
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

        assertThatThrownBy(() -> TenantPrivilege.elevatedInTransaction(transactionTemplate,
                () -> {
                    throw new Exception("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("elevated transaction failed")
                .hasRootCauseInstanceOf(Exception.class)
                .hasRootCauseMessage("boom");
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
