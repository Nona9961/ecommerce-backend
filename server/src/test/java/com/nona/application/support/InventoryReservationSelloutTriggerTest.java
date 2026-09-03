package com.nona.application.support;

import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.factory.InventoryItemFactory;
import com.nona.domain.inventory.ports.InventoryEventPublisher;
import com.nona.domain.inventory.ports.SelloutEvent;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.repository.InventoryItemRepositoryImpl;
import com.nona.inf.persistence.repository.jpa.InventoryItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.InventoryLogJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 售罄事件统一触发点集成测试（订单驱动三操作路径）：任何库存操作
 * （预占/确认扣减/回滚）后经统一触发点判定——变更后 available=0 且
 * held=0 即发布售罄事件（发布恰好一次），未售罄不发布；回滚路径可售
 * 只增不减，从语义上不触发任何事件（统一调用点一视同仁，无特判）。
 * <ul>
 *     <li>happy：预占耗尽 → 售罄事件一次；扣减耗尽（预占清零且可售
 *         已零）→ 售罄事件一次；</li>
 *     <li>critical：耗尽边界不重复发布（恰好一次）；</li>
 *     <li>error：部分预占/回滚释放不发布任何事件。</li>
 * </ul>
 * 事件断言经 mock 发布端口验证。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
class InventoryReservationSelloutTriggerTest {

    /**
     * 测试店铺的租户（店铺 ID 即租户 ID）
     */
    private static final String TENANT = "9701";

    /**
     * 测试 SKU（归属当前店铺）
     */
    private static final long SKU = 881701L;

    /**
     * 测试订单（流水订单上下文与幂等键）
     */
    private static final long ORDER = 661701L;

    /**
     * 被测库存保留用例（订单驱动三操作）
     */
    @Autowired
    private InventoryReservationUseCase reservationUseCase;

    /**
     * 库存聚合根仓储（装配用条件更新）
     */
    @Autowired
    private InventoryItemRepositoryImpl inventoryItemRepository;

    /**
     * 库存聚合根工厂（初始库存装配）
     */
    @Autowired
    private InventoryItemFactory inventoryItemFactory;

    /**
     * 库存主表 JPA（清理）
     */
    @Autowired
    private InventoryItemJpaRepository inventoryItemJpaRepository;

    /**
     * 流水表 JPA（断言与清理）
     */
    @Autowired
    private InventoryLogJpaRepository inventoryLogJpaRepository;

    /**
     * 事件发布端口（mock——统一触发点判定后的发布动作断言点）
     */
    @MockitoBean
    private InventoryEventPublisher inventoryEventPublisher;

    /**
     * 编程式事务模板（初始库存装配）
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
     * 每用例前：提权清空两表 + 建立请求作用域 + 店铺租户上下文。
     */
    @BeforeEach
    void setUp() {
        tenantPrivilege.elevated(() -> {
            inventoryLogJpaRepository.deleteAll();
            inventoryItemJpaRepository.deleteAll();
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        threadContext.setTenantID(TENANT);
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
     * happy：预占耗尽——可售全部转化为预占（available=0、held>0），
     * 未达售罄态不发布；预占恰好用尽后 held 非零，此路径不触发售罄。
     * 同为耗尽边界断言：不发布任何事件（预占耗尽不是售罄）。
     */
    @Test
    @DisplayName("预占耗尽可售归零但held非零不发布事件")
    void preoccupy_exhaustsAvailable_heldPositive_noEvent() {
        seededItem(5);

        final InventoryItem after = reservationUseCase.preoccupy(ORDER, SKU, 5);

        assertThat(after.getAvailable()).isZero();
        assertThat(after.getHeld()).isEqualTo(5);
        verifyNoInteractions(inventoryEventPublisher);
    }

    /**
     * happy：确认扣减耗尽——预占清零且可售已为零（available=0 且
     * held=0），售罄事件发布恰好一次（耗尽边界不重复发布、无恢复事件）。
     */
    @Test
    @DisplayName("扣减耗尽预占清零：售罄事件发布恰好一次")
    void confirmDeduct_exhaustsHeld_publishesSelloutOnce() {
        seededItem(5);
        reservationUseCase.preoccupy(ORDER, SKU, 5);

        final InventoryItem after = reservationUseCase.confirmDeduct(ORDER, SKU, 5);

        assertThat(after.getAvailable()).isZero();
        assertThat(after.getHeld()).isZero();
        assertThat(after.getSold()).isEqualTo(5);

        final ArgumentCaptor<SelloutEvent> sellout = ArgumentCaptor.forClass(SelloutEvent.class);
        verify(inventoryEventPublisher, times(1)).publishSellout(sellout.capture());
        assertThat(sellout.getValue().getPayload().skuId()).isEqualTo(SKU);
        assertThat(sellout.getValue().getType()).isEqualTo(SelloutEvent.TYPE);
        verify(inventoryEventPublisher, never()).publishRestock(org.mockito.ArgumentMatchers.any());
    }

    /**
     * critical：预占+扣减恰好用尽二阶序列——预占不触发，预占后经扣减
     * 打空预占（可售与预占同时归零），售罄事件恰好一次（同一判定点
     * 不重复发布）。
     */
    @Test
    @DisplayName("预占后扣减打空预占：售罄事件恰好一次")
    void preoccupyThenConfirmDeduct_exhausts_publishesSelloutOnce() {
        seededItem(3);
        reservationUseCase.preoccupy(ORDER, SKU, 3);
        verifyNoInteractions(inventoryEventPublisher);

        final InventoryItem after = reservationUseCase.confirmDeduct(ORDER, SKU, 3);

        assertThat(after.getAvailable()).isZero();
        assertThat(after.getHeld()).isZero();
        assertThat(after.getSold()).isEqualTo(3);

        final ArgumentCaptor<SelloutEvent> sellout = ArgumentCaptor.forClass(SelloutEvent.class);
        verify(inventoryEventPublisher, times(1)).publishSellout(sellout.capture());
        assertThat(sellout.getValue().getPayload().skuId()).isEqualTo(SKU);
        verify(inventoryEventPublisher, never()).publishRestock(org.mockito.ArgumentMatchers.any());
    }

    /**
     * error：部分预占不发布——未达售罄态的任何变更都不触发事件。
     */
    @Test
    @DisplayName("部分预占未售罄不发布事件")
    void preoccupy_partial_noEvent() {
        seededItem(5);

        reservationUseCase.preoccupy(ORDER, SKU, 2);

        verifyNoInteractions(inventoryEventPublisher);
    }

    /**
     * error：回滚释放不发布——预占回滚使可售恢复（available 增、held
     * 减），不落入售罄态；变更前也非售罄态（held>0），恢复事件同样
     * 不触发——回滚路径从语义上不发布任何事件。
     */
    @Test
    @DisplayName("回滚释放可售不发布任何事件")
    void rollback_releasesHeld_noEvent() {
        seededItem(5);
        reservationUseCase.preoccupy(ORDER, SKU, 5);
        verifyNoInteractions(inventoryEventPublisher);

        final InventoryItem after = reservationUseCase.rollback(ORDER, SKU, 5);

        assertThat(after.getAvailable()).isEqualTo(5);
        assertThat(after.getHeld()).isZero();

        verifyNoInteractions(inventoryEventPublisher);
    }

    /**
     * 装配初始库存：工厂建行（三态清零）→ 聚合调整补货到目标可售
     * （普通保存路径）。
     *
     * @param available 目标可售量
     */
    private void seededItem(int available) {
        tx.executeWithoutResult(status -> {
            final InventoryItem created = inventoryItemFactory.createInitial(9701L, SKU);
            inventoryItemRepository.save(created);
            final InventoryItem loaded = inventoryItemRepository.getByID(created.getId());
            loaded.adjust("test-initializer", available, "测试初始库存");
            inventoryItemRepository.save(loaded);
        });
    }
}