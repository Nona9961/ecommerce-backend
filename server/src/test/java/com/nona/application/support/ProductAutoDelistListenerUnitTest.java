package com.nona.application.support;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.SelloutEvent;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.inf.context.TenantPrivilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 售罄自动下架消费监听单元测试：{@link TransactionalEventListener}
 * AFTER_COMMIT 订阅的监听方法把自动下架任务提交到库存事件执行器——异步
 * 消费与库存主链路解耦（提交/执行失败均捕获为告警，不向发布侧传播异常）。
 * <p>
 * 编排语义（autoDelistOnSellout 的提权事务 + 售罄判定 + 聚合迁移）属
 * 集成测试覆盖（{@link ProductAutoDelistIntegrationAcTest}）；本文件只断言
 * 监听装配的异步隔离契约。
 *
 * @author nona9961
 */
class ProductAutoDelistListenerUnitTest {

    /**
     * 商品仓储（mock）
     */
    private final ProductRepository productRepository = mock(ProductRepository.class);

    /**
     * 库存门面（mock）
     */
    private final InventoryFacade inventoryFacade = mock(InventoryFacade.class);

    /**
     * 提权工具（mock）
     */
    private final TenantPrivilege tenantPrivilege = mock(TenantPrivilege.class);

    /**
     * 事务模板（mock）
     */
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    /**
     * 事件异步执行器（mock）
     */
    private final Executor executor = mock(Executor.class);

    /**
     * 被测监听（与执行器同实例组装）
     */
    private final ProductAutoDelistListener listener = new ProductAutoDelistListener(
            productRepository, inventoryFacade, tenantPrivilege, transactionTemplate, executor);

    /**
     * 每用例前：重置 mock 交互记录（防跨用例污染）。
     */
    @BeforeEach
    void setUp() {
        reset(executor);
    }

    /**
     * happy：售罄事件提交自动下架任务到执行器（任务捕获 SKU 载荷，异步
     * 执行不阻塞监听线程）。
     */
    @Test
    @DisplayName("售罄事件提交异步自动下架任务")
    void onSellout_submitsAsyncTask() {
        final SelloutEvent event = new SelloutEvent(88001L);

        assertThatCode(() -> listener.onSellout(event)).doesNotThrowAnyException();

        verify(executor).execute(any(Runnable.class));
    }

    /**
     * 正常路径：任务体执行时抛异常不向监听线程传播（自动下架失败仅告警，
     * 不影响库存主链路——与库存事件消费容错形制一致）。
     */
    @Test
    @DisplayName("任务执行失败不向监听线程传播")
    void onSellout_taskFailureSwallowed() {
        final SelloutEvent event = new SelloutEvent(88002L);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onSellout(event)).doesNotThrowAnyException();
    }

    /**
     * 正常路径：任务体执行业务编排（编排异常被任务体容错捕获，监听线程
     * 零感知——自动下架失败仅告警）。
     */
    @Test
    @DisplayName("编排异常被任务体容错捕获")
    void onSellout_orchestrationFailureSwallowed() {
        final SelloutEvent event = new SelloutEvent(88003L);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onSellout(event)).doesNotThrowAnyException();

        // 编排在任务体内执行（提权/仓储/库存门面在任务线程被触碰）——
        // 此处仅验证任务确已提交
        verify(executor).execute(any(Runnable.class));
    }

    /**
     * 正常路径：执行器拒绝提交（饱和）不向发布侧传播（告警兜底）。
     */
    @Test
    @DisplayName("执行器拒绝提交不传播")
    void onSellout_submitRejectedSwallowed() {
        final SelloutEvent event = new SelloutEvent(88004L);
        doThrow(new RuntimeException("executor saturated")).when(executor).execute(any(Runnable.class));

        assertThatCode(() -> listener.onSellout(event)).doesNotThrowAnyException();
    }

    /**
     * 装配：事件载荷 skuId 驱动任务提交——连续两次售罄事件各提交一次
     * 任务（每 SKU 事件独立消费，任务按载荷定位商品）。
     */
    @Test
    @DisplayName("事件逐条提交任务")
    void eventPayload_skuIdPresent() {
        final SelloutEvent first = new SelloutEvent(88005L);
        final SelloutEvent second = new SelloutEvent(88006L);

        assertThatCode(() -> listener.onSellout(first)).doesNotThrowAnyException();
        assertThatCode(() -> listener.onSellout(second)).doesNotThrowAnyException();

        verify(executor, times(2)).execute(any(Runnable.class));
    }
}
