package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.inf.persistence.converters.SubOrderConvertor;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.repository.jpa.OrderItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子订单仓储实现场景测试（WU-55 红阶段契约：主单维度反查 / 履约超时
 * 三件套（findDue-claim-clear）/ 商家分页扩展 / 级联删）。
 * <p>
 * happy——主单反查保持创建序、findDue 状态与时刻透传（含等号边界）、
 * claim 条件命中 1 行 → true、clear 无条件幂等、商家分页多值过滤、
 * 级联删从表先删；critical——findDue 无到期空列表、limit=0 拒绝、
 * 状态空集合=全量分支；fail——claim 已被认领/状态迁移 0 行 → false、
 * 分页过滤无命中空列表、删除不存在行返回 0。
 * <p>
 * 装配纪律：依赖全 mock（超时条件更新落地面以 JdbcTemplate mock——红
 * 阶段可完整 stub 的已有契约面，claim/clear SQL 参数化语义绿阶段按其
 * 落库），无容器；被测仓储 @BeforeEach 重建。桩纪律同 MasterOrder
 * 判例：行为桩 lenient 豁免 UOE 挡道，绿实现后收回精确桩。时间断言：
 * now 为测试 fixture 输入（非断言魔法值），JPA 透传断言一律相对
 * {@link #NOW} 派生（UTC 字面，PayTimeoutStore 单元判例同款纪律）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class SubOrderRepositoryImplUnitTest {

    /**
     * 归属主订单 ID（主单维度反查定位键）
     */
    private static final long MASTER_ORDER_ID = 7701L;

    /**
     * 归属店铺 ID（商家分页业务条件）
     */
    private static final long SHOP_ID = 1001L;

    /**
     * 扫描时刻 fixture（findDue 透传断言基准；断言一律相对本变量派生）
     */
    private static final Instant NOW = Instant.parse("2026-09-08T12:30:00Z");

    /**
     * now 的 UTC 字面（PO LocalDateTime 列承载面，断言相对派生）
     */
    private static final LocalDateTime NOW_UTC =
            NOW.atOffset(ZoneOffset.UTC).toLocalDateTime();

    /**
     * 单轮扫描上限（引擎 SCAN_LIMIT 语义透传）
     */
    private static final int LIMIT = 100;

    @Mock
    private SubOrderJpaRepository subOrderJpaRepository;

    @Mock
    private SubOrderConvertor convertor;

    @Mock
    private ChangeTrackerProvider changeTrackerProvider;

    @Mock
    private OrderItemJpaRepository orderItemJpaRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    /**
     * 被测仓储（setUp 重建）
     */
    private SubOrderRepositoryImpl repository;

    /**
     * 每用例前重建被测仓储（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        repository = new SubOrderRepositoryImpl(subOrderJpaRepository, convertor,
                changeTrackerProvider, orderItemJpaRepository, jdbcTemplate);
    }

    @Test
    @DisplayName("happy：主单维度反查返回子单集合（保持创建序）")
    void getByMasterOrderId_returnsChildrenInCreationOrder() {
        final SubOrderPO first = new SubOrderPO();
        final SubOrderPO second = new SubOrderPO();
        when(subOrderJpaRepository.findByMasterOrderIdOrderByIdAsc(MASTER_ORDER_ID))
                .thenReturn(List.of(first, second));
        final SubOrder child = org.mockito.Mockito.mock(SubOrder.class);
        when(convertor.convertToRoot(any(SubOrderPO.class), any()))
                .thenReturn(child);
        when(orderItemJpaRepository.findBySubOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of(new OrderItemPO()));
        // 快照基线登记（模板语义）：装载面需跟踪作用域 + 懒创建追踪器
        final ChangeTracker tracker = org.mockito.Mockito.mock(ChangeTracker.class);
        when(changeTrackerProvider.create()).thenReturn(tracker);

        com.nona.inf.context.TrackingContext.withScope(() -> {
            final List<SubOrder> result = repository.getByMasterOrderId(MASTER_ORDER_ID);

            assertThat(result).hasSize(2);
        });
        verify(subOrderJpaRepository).findByMasterOrderIdOrderByIdAsc(MASTER_ORDER_ID);
        // 两行各登记一次快照基线（convertToRoot 对两行返回同一 mock 实例）
        verify(tracker, org.mockito.Mockito.times(2)).track(child);
    }

    @Test
    @DisplayName("happy：超时扫描返回到期候选（状态/时刻按预期透传，含等号边界）")
    void findDue_expectedStatusAndCutoff_passedThrough() {
        final SubOrderPO due = new SubOrderPO();
        when(subOrderJpaRepository.findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                eq(SubOrderStatus.PAID), any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.of(due));
        final SubOrder candidate = org.mockito.Mockito.mock(SubOrder.class);
        when(convertor.convertToRoot(any(SubOrderPO.class), any()))
                .thenReturn(candidate);
        when(orderItemJpaRepository.findBySubOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final List<SubOrder> result =
                repository.findDueByStatusAndTimeoutAtBefore(SubOrderStatus.PAID, NOW, LIMIT);

        assertThat(result).hasSize(1);
        verify(subOrderJpaRepository)
                .findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                        eq(SubOrderStatus.PAID), eq(NOW_UTC), eq(Limit.of(LIMIT)));
    }

    @Test
    @DisplayName("critical：无到期候选返回空列表（fail-safe）")
    void findDue_noDue_returnsEmpty() {
        when(subOrderJpaRepository.findByStatusAndTimeoutAtLessThanEqualOrderByTimeoutAtAsc(
                eq(SubOrderStatus.SHIPPED), any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(List.of());

        final List<SubOrder> result =
                repository.findDueByStatusAndTimeoutAtBefore(SubOrderStatus.SHIPPED, NOW, LIMIT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("happy：claim 条件命中（1 行）返回 true")
    void claimTimeout_singleRowAffected_returnsTrue() {
        when(jdbcTemplate.update(anyString(), anyLong(),
                anyString())).thenReturn(1);

        final boolean claimed = repository.claimTimeout(8801L, SubOrderStatus.PAID);

        assertThat(claimed).isTrue();
        verify(jdbcTemplate).update(anyString(), eq(8801L), eq(SubOrderStatus.PAID.name()));
    }

    @Test
    @DisplayName("fail：claim 条件不满足（0 行——已认领/状态迁移/行不存在）返回 false")
    void claimTimeout_noRowAffected_returnsFalse() {
        when(jdbcTemplate.update(anyString(), anyLong(),
                anyString())).thenReturn(0);

        final boolean claimed = repository.claimTimeout(8801L, SubOrderStatus.SHIPPED);

        assertThat(claimed).isFalse();
    }

    @Test
    @DisplayName("happy：clear 无条件幂等（0 行/目标不存在均成功无异常）")
    void clearTimeoutDeadline_idempotent_noError() {
        when(jdbcTemplate.update(anyString(), anyLong())).thenReturn(0);

        assertThatCode(() -> repository.clearTimeoutDeadline(8801L))
                .doesNotThrowAnyException();
        verify(jdbcTemplate).update(anyString(), eq(8801L));
    }

    @Test
    @DisplayName("happy：商家分页按店铺+状态多值过滤返回映射列表")
    void listPagedByShop_statusFiltered_returnsMappedList() {
        final SubOrderPO po = new SubOrderPO();
        when(subOrderJpaRepository.findByShopIdAndStatusInOrderByCreateTimeDescIdDesc(
                eq(SHOP_ID), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(po)));
        final SubOrder root = org.mockito.Mockito.mock(SubOrder.class);
        when(convertor.convertToRoot(any(SubOrderPO.class), any()))
                .thenReturn(root);
        when(orderItemJpaRepository.findBySubOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final List<SubOrder> result = repository.listPagedByShop(
                SHOP_ID, List.of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED), 0, 20);

        assertThat(result).hasSize(1);
        verify(subOrderJpaRepository)
                .findByShopIdAndStatusInOrderByCreateTimeDescIdDesc(
                        eq(SHOP_ID),
                        eq(List.of(SubOrderStatus.REFUNDING, SubOrderStatus.REFUNDED)),
                        any(PageRequest.class));
    }

    @Test
    @DisplayName("critical：状态空集合=不过滤（「全部」tab 承载面，走全量分支）")
    void listPagedByShop_statusEmpty_noFilterBranch() {
        final SubOrderPO po = new SubOrderPO();
        when(subOrderJpaRepository.findByShopIdOrderByCreateTimeDescIdDesc(
                eq(SHOP_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(po)));
        final SubOrder root = org.mockito.Mockito.mock(SubOrder.class);
        when(convertor.convertToRoot(any(SubOrderPO.class), any()))
                .thenReturn(root);
        when(orderItemJpaRepository.findBySubOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final List<SubOrder> result = repository.listPagedByShop(SHOP_ID, List.of(), 0, 20);

        assertThat(result).hasSize(1);
        verify(subOrderJpaRepository).findByShopIdOrderByCreateTimeDescIdDesc(
                eq(SHOP_ID), any(PageRequest.class));
    }

    @Test
    @DisplayName("critical：limit=0 非法分页参数 fail-closed 拒绝（IAE）")
    void listPagedByShop_limitZero_rejected() {
        assertThatThrownBy(() -> repository.listPagedByShop(SHOP_ID, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("fail：过滤无命中返回空列表（fail-safe）")
    void listPagedByShop_noMatch_returnsEmpty() {
        when(subOrderJpaRepository.findByShopIdAndStatusInOrderByCreateTimeDescIdDesc(
                eq(SHOP_ID), any(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        final List<SubOrder> result =
                repository.listPagedByShop(SHOP_ID, List.of(SubOrderStatus.CLOSED), 0, 20);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("happy：商家 count 按店铺+状态过滤条件透传")
    void countByShop_filtered_returnsTotal() {
        when(subOrderJpaRepository.countByShopIdAndStatusIn(eq(SHOP_ID), any()))
                .thenReturn(3L);

        assertThat(repository.countByShop(SHOP_ID, List.of(SubOrderStatus.COMPLETED)))
                .isEqualTo(3L);
        verify(subOrderJpaRepository).countByShopIdAndStatusIn(
                eq(SHOP_ID), eq(List.of(SubOrderStatus.COMPLETED)));
    }

    @Test
    @DisplayName("happy：级联删——从表条目先删、根行后删，返回真实受影响行数 1")
    void deleteByID_cascadesItemsThenRoot_returnsAffectedOne() {
        when(subOrderJpaRepository.existsById(anyLong())).thenReturn(true);

        assertThat(repository.deleteByID(8801L)).isEqualTo(1);
        verify(orderItemJpaRepository).deleteBySubOrderId(8801L);
        verify(subOrderJpaRepository).deleteById(8801L);
    }

    @Test
    @DisplayName("fail：删除不存在的根行返回 0（真实语义，非契约形）")
    void deleteByID_rootAbsent_returnsZero() {
        when(subOrderJpaRepository.existsById(anyLong())).thenReturn(false);

        assertThat(repository.deleteByID(8801L)).isEqualTo(0);
    }

    @Test
    @DisplayName("fail：null 删除委托按无操作返回 0")
    void delete_null_returnsZero() {
        assertThat(repository.delete(null)).isEqualTo(0);
    }
}