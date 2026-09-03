package com.nona.application.seller;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLogType;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.domain.inventory.ports.InventoryEventPublisher;
import com.nona.domain.inventory.ports.RestockEvent;
import com.nona.domain.inventory.ports.SelloutEvent;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.po.inventory.InventoryItemPO;
import com.nona.inf.persistence.po.inventory.InventoryLogPO;
import com.nona.inf.persistence.repository.InventoryItemRepositoryImpl;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 商家手工调整可售用例集成测试：adjustStock 全链路契约——操作人必填
 * 守卫 + 条件更新（防调整致负并发防线）+ MANUAL_ADJUST 流水同事务 +
 * 售罄/恢复事件统一触发点接入。
 * <ul>
 *     <li>happy：增可售/减可售成功（三态推进、version +1、流水
 *         before/after/delta 带符号落库）；</li>
 *     <li>critical：调整致可售恰归零触发售罄事件（发布恰好一次）、
 *         held>0 时归零不触发、可售恢复发布恢复事件、调整不动预占；</li>
 *     <li>error：调整致负拒绝（不动库存不产流水）、跨店铺不可见、
 *         缺操作人拒绝。</li>
 * </ul>
 * 事件断言经 mock 发布端口（统一触发点判定后的发布动作）验证；断言
 * 一律落库后核验（PO 层直读）。并发调整用例验证条件更新的防负并发
 * 防线（两线程竞争减可售，恰一笔成功、不出现负值）。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryUseCaseAdjustIntegrationTest {

    /**
     * 测试店铺 A 的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT_A = "9601";

    /**
     * 测试店铺 B 的租户（跨店铺拒绝断言用）
     */
    private static final String TENANT_B = "9602";

    /**
     * 测试 SKU（归属 A 店铺）
     */
    private static final long SKU = 881601L;

    /**
     * 测试店铺 ID（与租户值一致）
     */
    private static final long SHOP_A = 9601L;

    /**
     * 被测商家端库存用例
     */
    @Autowired
    private InventoryUseCase inventoryUseCase;

    /**
     * 库存聚合根仓储（条件更新方法直接断言用）
     */
    @Autowired
    private InventoryItemRepositoryImpl inventoryItemRepository;

    /**
     * 库存聚合根工厂（初始库存装配）
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

    /**
     * 库存主表 JPA（断言行与清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（断言行与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 事件发布端口（mock——统一触发点判定后的发布动作断言点）
     */
    @MockitoBean
    private InventoryEventPublisher inventoryEventPublisher;

    /**
     * 编程式事务模板（初始库存装配：工厂建行 + 聚合调整补货 + CAS 预占）
     */
    @Autowired
    private TransactionTemplate tx;

    /**
     * 请求级上下文（模拟商家请求租户=当前店铺）
     */
    @Autowired
    private ThreadContext threadContext;

    /**
     * 提权工具（测试数据清理需要越过租户过滤）
     */
    @Autowired
    private TenantPrivilege tenantPrivilege;

    /**
     * 每用例前：提权清空两表 + 建立请求作用域 + 店铺 A 租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        threadContext.setTenantID(TENANT_A);
    }

    /**
     * 每用例后：清理请求作用域与租户上下文，避免跨用例污染。
     */
    @AfterEach
    void tearDown() {
        threadContext.setTenantID(null);
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * happy：增可售调整成功——可售增加、预占/已售不动、version 逐笔
     * +1，MANUAL_ADJUST 流水一行落库（before/after 三态快照、带符号
     * delta、操作人、原因、无订单上下文齐备）。
     */
    @Test
    @DisplayName("增可售成功：三态推进且流水带符号落库")
    void adjustStock_increasesAvailable_keepsHeldAndLogs() {
        final InventoryItem item = seededItem(10);

        final InventoryItem after = inventoryUseCase.adjustStock(
                SHOP_A, SKU, 5, "seller-1", "补货入库");

        assertThat(after.getAvailable()).isEqualTo(15);
        assertThat(after.getHeld()).isZero();
        assertThat(after.getSold()).isZero();
        assertThat(after.getVersion()).isEqualTo(2);

        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getAvailable()).isEqualTo(15);
        assertThat(po.getHeld()).isZero();
        assertThat(po.getSold()).isZero();
        assertThat(po.getVersion()).isEqualTo(2);

        final InventoryLogPO log = latestAdjustLog();
        assertThat(log.getType()).isEqualTo(InventoryLogType.MANUAL_ADJUST);
        assertThat(log.getDelta()).isEqualTo(5);
        assertThat(log.getOrderId()).isNull();
        assertThat(log.getOperator()).isEqualTo("seller-1");
        assertThat(log.getReason()).isEqualTo("补货入库");
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getBeforeHeld()).isZero();
        assertThat(log.getBeforeSold()).isZero();
        assertThat(log.getAfterAvailable()).isEqualTo(15);
        assertThat(log.getAfterHeld()).isZero();
        assertThat(log.getAfterSold()).isZero();
    }

    /**
     * happy：减可售调整成功——delta 带符号（负）落库，可售减少。
     */
    @Test
    @DisplayName("减可售成功：负 delta 落库且流水快照正确")
    void adjustStock_decreasesAvailable_withNegativeDelta() {
        final InventoryItem item = seededItem(10);

        final InventoryItem after = inventoryUseCase.adjustStock(
                SHOP_A, SKU, -3, "seller-2", "盘点扣减");

        assertThat(after.getAvailable()).isEqualTo(7);
        assertThat(after.getVersion()).isEqualTo(2);

        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getAvailable()).isEqualTo(7);

        final InventoryLogPO log = latestAdjustLog();
        assertThat(log.getDelta()).isEqualTo(-3);
        assertThat(log.getBeforeAvailable()).isEqualTo(10);
        assertThat(log.getAfterAvailable()).isEqualTo(7);
        assertThat(log.getOperator()).isEqualTo("seller-2");
    }

    /**
     * critical：调整致可售恰归零（available=0 且 held=0）——售罄事件
     * 发布恰好一次（耗尽边界不重复发布），无恢复事件。
     */
    @Test
    @DisplayName("调整致可售恰归零：售罄事件发布恰好一次")
    void adjustStock_exactZero_publishesSelloutOnce() {
        seededItem(5);

        inventoryUseCase.adjustStock(SHOP_A, SKU, -5, "seller-1", "清仓");

        final ArgumentCaptor<SelloutEvent> sellout = ArgumentCaptor.forClass(SelloutEvent.class);
        verify(inventoryEventPublisher, times(1)).publishSellout(sellout.capture());
        assertThat(sellout.getValue().getPayload().skuId()).isEqualTo(SKU);
        assertThat(sellout.getValue().getType()).isEqualTo(SelloutEvent.TYPE);
        assertThat(sellout.getValue().timestamp()).isNotNull();
        verify(inventoryEventPublisher, never()).publishRestock(org.mockito.ArgumentMatchers.any());
    }

    /**
     * critical：可售归零但 held>0（在途预占未清）——非售罄态，不发布
     * 任何事件；调整不动预占（held 保持不变断言）。
     */
    @Test
    @DisplayName("held大于零时可售归零不触发售罄且预占不动")
    void adjustStock_zeroAvailableWithHeldPositive_noEvent_heldUntouched() {
        final InventoryItem item = seededItemWithHeld(2, 4);

        final InventoryItem after = inventoryUseCase.adjustStock(
                SHOP_A, SKU, -2, "seller-1", "减可售至零");

        assertThat(after.getAvailable()).isZero();
        assertThat(after.getHeld()).isEqualTo(4);
        assertThat(after.getSold()).isZero();
        assertThat(after.getVersion()).isEqualTo(3);

        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getAvailable()).isZero();
        assertThat(po.getHeld()).isEqualTo(4);
        assertThat(po.getVersion()).isEqualTo(3);

        final InventoryLogPO log = latestAdjustLog();
        assertThat(log.getBeforeHeld()).isEqualTo(4);
        assertThat(log.getAfterHeld()).isEqualTo(4);
        assertThat(log.getDelta()).isEqualTo(-2);

        verifyNoInteractions(inventoryEventPublisher);
    }

    /**
     * critical：可售恢复——此前已售罄（available=0 且 held=0）经调整
     * 恢复可售（available>0），发布恢复事件（一期日志消费）。
     */
    @Test
    @DisplayName("售罄后调整恢复可售：发布恢复事件")
    void adjustStock_restoreFromSellout_publishesRestock() {
        seededItem(3);
        inventoryUseCase.adjustStock(SHOP_A, SKU, -3, "seller-1", "售罄");

        inventoryUseCase.adjustStock(SHOP_A, SKU, 5, "seller-1", "补货上架");

        final ArgumentCaptor<RestockEvent> restock = ArgumentCaptor.forClass(RestockEvent.class);
        verify(inventoryEventPublisher, times(1)).publishRestock(restock.capture());
        assertThat(restock.getValue().getPayload().skuId()).isEqualTo(SKU);
        assertThat(restock.getValue().getType()).isEqualTo(RestockEvent.TYPE);
        verify(inventoryEventPublisher, times(1)).publishSellout(org.mockito.ArgumentMatchers.any());
    }

    /**
     * error：调整致负拒绝——调整后可售为负时业务异常拒绝（库存不足
     * 语义），库存三态不动、不产本笔流水（装配基线流水 0 行——seededItem
     * 普通保存路径不追加流水）、不发布任何事件。
     */
    @Test
    @DisplayName("调整致负被拒绝且不动库存不产流水")
    void adjustStock_negativeRejected_noStateNoLog() {
        final InventoryItem item = seededItem(3);

        assertThatThrownBy(() -> inventoryUseCase.adjustStock(SHOP_A, SKU, -5, "seller-1", "扣减"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code());

        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getAvailable()).isEqualTo(3);
        assertThat(po.getHeld()).isZero();
        assertThat(po.getVersion()).isEqualTo(1);
        assertThat(adjustLogs()).hasSize(0);
        verifyNoInteractions(inventoryEventPublisher);
    }

    /**
     * error：跨店铺调整不可见——店铺 B 的请求上下文对店铺 A 的 SKU
     * 库存执行调整，按不存在拒绝（归属不泄露），行内容不变。
     */
    @Test
    @DisplayName("跨店铺调整按不存在拒绝且行内容不变")
    void adjustStock_crossTenant_notFound() {
        final InventoryItem item = seededItem(10);

        threadContext.setTenantID(TENANT_B);
        assertThatThrownBy(() -> inventoryUseCase.adjustStock(9602L, SKU, 1, "seller-2", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_NOT_FOUND.code());

        threadContext.setTenantID(TENANT_A);
        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getAvailable()).isEqualTo(10);
        assertThat(po.getVersion()).isEqualTo(1);
        assertThat(adjustLogs()).hasSize(0);
    }

    /**
     * error：缺操作人拒绝——调整是审计追踪语义，认证身份缺失（操作
     * 人不可确定）时用例入口拒绝，不进入库存变更路径。
     */
    @Test
    @DisplayName("缺操作人的调整被拒绝")
    void adjustStock_missingOperator_rejected() {
        seededItem(10);

        assertThatThrownBy(() -> inventoryUseCase.adjustStock(SHOP_A, SKU, 1, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo(EcommerceBusinessCode.INVENTORY_LOG_INVALID.code());

        assertThat(adjustLogs()).hasSize(0);
    }

    /**
     * error：并发调整防负——两线程同时对同一库存减可售（初始可售不
     * 够两笔全额），条件更新并发防线（available + delta >= 0 基于 DB
     * 当前值判定）恰好放行一笔，另一笔以库存不足拒绝；最终可售为
     * 初始值减成功笔调整量、不出现负值，失败笔不产流水。
     */
    @Test
    @DisplayName("并发减可售恰一笔成功且不出现负值")
    void adjustStock_concurrentDecrease_singleSuccess() throws Exception {
        final InventoryItem item = seededItem(10);
        final java.util.concurrent.CountDownLatch startLatch =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger success =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger insufficient =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger unexpected =
                new java.util.concurrent.atomic.AtomicInteger();

        final List<Thread> workers = new java.util.ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            final Thread worker = Thread.ofVirtual().name("adjust-worker-" + i).start(() -> {
                try {
                    startLatch.await();
                    RequestContextHolder.setRequestAttributes(
                            new ServletRequestAttributes(new MockHttpServletRequest()));
                    try {
                        threadContext.setTenantID(TENANT_A);
                        inventoryUseCase.adjustStock(SHOP_A, SKU, -6, "seller-1", "并发扣减");
                        success.incrementAndGet();
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    unexpected.incrementAndGet();
                } catch (final BusinessException e) {
                    if (EcommerceBusinessCode.INVENTORY_INSUFFICIENT.code().equals(e.getBusinessCode())) {
                        insufficient.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (final Throwable t) {
                    unexpected.incrementAndGet();
                }
            });
            workers.add(worker);
        }
        startLatch.countDown();
        for (final Thread worker : workers) {
            worker.join(60_000);
        }

        assertThat(success).hasValue(1);
        assertThat(insufficient).hasValue(1);
        assertThat(unexpected).hasValue(0);
        final InventoryItemPO po = inventoryItemJpaRepository.findById(item.getId()).orElseThrow();
        assertThat(po.getAvailable()).isEqualTo(4);
        assertThat(po.getHeld()).isZero();
        assertThat(po.getVersion()).isEqualTo(2);
        assertThat(adjustLogs()).hasSize(1);
    }

    /**
     * 装配初始库存：工厂建行（三态清零）→ 聚合调整补货到目标可售
     * （普通保存路径，与条件更新路径分离）。
     *
     * @param available 目标可售量
     * @return 初始库存聚合
     */
    private InventoryItem seededItem(int available) {
        return tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(SHOP_A, SKU);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", available, "测试初始库存");
            inventoryItemRepository.save(loaded);
            return loaded;
        });
    }

    /**
     * 装配带在途预占的库存：先补货到 (available + held)，再经条件更新
     * 预占 held 份（已实现路径——预占使可售减、预占增），得到
     * (available, held) 目标三态。
     *
     * @param available 目标可售量
     * @param held      目标预占量
     * @return 初始库存聚合（含在途预占）
     */
    private InventoryItem seededItemWithHeld(int available, int held) {
        return tx.execute(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(SHOP_A, SKU);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", available + held, "测试初始库存");
            inventoryItemRepository.save(loaded);
            final int affected = inventoryItemRepository.casPreoccupy(loaded.getId(), held);
            if (affected != 1) {
                throw new IllegalStateException("预占装配失败");
            }
            return loaded;
        });
    }

    /**
     * 取该 SKU 最新一条 MANUAL_ADJUST 流水。
     *
     * @return 最新调整流水行
     */
    private InventoryLogPO latestAdjustLog() {
        return adjustLogs().getFirst();
    }

    /**
     * 取该 SKU 的 MANUAL_ADJUST 流水行（新行在前）。
     *
     * @return 调整流水行列表
     */
    private List<InventoryLogPO> adjustLogs() {
        return inventoryLogJpaRepository
                .findBySkuIdOrderByIdDesc(SKU, PageRequest.of(0, 100))
                .stream()
                .filter(log -> log.getType() == InventoryLogType.MANUAL_ADJUST)
                .toList();
    }
}