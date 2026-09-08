package com.nona.domain.logistics.service;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;
import com.nona.domain.logistics.ports.PlatformLogisticsViewItem;
import com.nona.domain.logistics.repo.PlatformLogisticsViewRepository;
import com.nona.domain.logistics.repo.PlatformLogisticsViewRow;
import com.nona.domain.order.entity.SubOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台物流视图查询服务场景测试（P5.1：跨店铺物流列表/筛选/分页/
 * 超时未发货标记，红阶段契约）。
 * <p>
 * 覆盖：happy——全链路混合行（已发货行 + 超时未发货行）标记语义与
 * 字段透传、筛选/分页原样透传（同条件两查编序 search→count）、
 * 判定公式矩阵（设计物直测）；critical——空列表、末页截断、
 * 全平台视角与无 deadline 不标记；fail——仓储异常透传（行查询/
 * 计数两路、失败短路不计数）、仓储违约 null 防御（不 NPE）。
 * <p>
 * 依赖装配：读模型仓储以 mock 承载（服务编排契约断言面），被测服务
 * 每用例前重建；红阶段失败原因 = 服务方法体未接线（UOE），而非语法/
 * 装配错误。
 * <p>
 * 时间断言：全部相对窗口（now ± Duration），零绝对日期魔法值；判定
 * 时刻与服务执行时刻差不超过毫秒级，fixture 余量（2 小时/1 小时）
 * 远大于窗口抖动。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class PlatformLogisticsViewServiceUnitTest {

    /**
     * 测试店铺 / 主单 / 子单 / 运单常量
     */
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;
    private static final long MASTER_ID = 100L;
    private static final long SUB_1 = 101L;
    private static final long SUB_2 = 102L;
    private static final long WAYBILL_ID = 9001L;
    private static final String SHOP_A_NAME = "店铺A";
    private static final String COMPANY = "顺丰速运";
    private static final String TRACKING_NO = "SF202609080001";

    @Mock
    private PlatformLogisticsViewRepository platformLogisticsViewRepository;

    /**
     * 被测服务（红阶段不注册 Spring；依赖全 mock，setUp 装配）。
     */
    private PlatformLogisticsViewServiceImpl service;

    /**
     * 每用例前重建被测服务（mock 桩在各用例内布置，桩全部被使用）。
     */
    @BeforeEach
    void setUp() {
        service = new PlatformLogisticsViewServiceImpl(platformLogisticsViewRepository);
    }

    /**
     * 行投影快捷工厂（未发货 = 运单字段空）。
     */
    private static PlatformLogisticsViewRow row(Long subOrderId, SubOrderStatus subStatus,
                                                Instant timeoutAt, Long waybillId,
                                                WaybillStatus waybillStatus) {
        return new PlatformLogisticsViewRow(subOrderId, "SUB" + subOrderId, MASTER_ID,
                SHOP_A, SHOP_A_NAME, subStatus, waybillId,
                waybillId == null ? null : COMPANY,
                waybillId == null ? null : TRACKING_NO, waybillStatus, timeoutAt);
    }

    // ---------- happy path ----------

    /**
     * happy-1 全链路：店铺筛选 + 分页 → 混合行（已发货行
     * waybill 附注 + 未发货超期行 timeoutOverdue=true）字段透传、
     * total/页码回显、默认排序序（仓储已排，服务保序）。
     */
    @Test
    @DisplayName("happy：跨店列表全链路——混合行标记与物流字段透传")
    void fullListMixesShippedAndOverdueRows() {
        Instant now = Instant.now();
        PlatformLogisticsViewRow shipped =
                row(SUB_1, SubOrderStatus.SHIPPED, now.minus(Duration.ofHours(2)),
                        WAYBILL_ID, WaybillStatus.IN_TRANSIT);
        PlatformLogisticsViewRow overdue =
                row(SUB_2, SubOrderStatus.PAID, now.minus(Duration.ofHours(2)), null, null);
        when(platformLogisticsViewRepository.search(
                new PlatformLogisticsViewFilter(SHOP_A, null), new PageQuery(1, 10)))
                .thenReturn(List.of(shipped, overdue));
        when(platformLogisticsViewRepository.count(
                new PlatformLogisticsViewFilter(SHOP_A, null))).thenReturn(2L);

        PageResult<PlatformLogisticsViewItem> result =
                service.list(new PlatformLogisticsViewFilter(SHOP_A, null), new PageQuery(1, 10));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.pageNum()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.records()).hasSize(2);
        PlatformLogisticsViewItem first = result.records().get(0);
        assertThat(first.subOrderId()).isEqualTo(SUB_1);
        assertThat(first.subOrderNo()).isEqualTo("SUB101");
        assertThat(first.shopId()).isEqualTo(SHOP_A);
        assertThat(first.shopName()).isEqualTo(SHOP_A_NAME);
        assertThat(first.subOrderStatus()).isEqualTo(SubOrderStatus.SHIPPED);
        assertThat(first.waybillId()).isEqualTo(WAYBILL_ID);
        assertThat(first.company()).isEqualTo(COMPANY);
        assertThat(first.trackingNo()).isEqualTo(TRACKING_NO);
        assertThat(first.waybillStatus()).isEqualTo(WaybillStatus.IN_TRANSIT);
        assertThat(first.timeoutOverdue()).isFalse();
        PlatformLogisticsViewItem second = result.records().get(1);
        assertThat(second.subOrderStatus()).isEqualTo(SubOrderStatus.PAID);
        assertThat(second.waybillId()).isNull();
        assertThat(second.company()).isNull();
        assertThat(second.trackingNo()).isNull();
        assertThat(second.waybillStatus()).isNull();
        assertThat(second.timeoutAt()).isNotNull();
        assertThat(second.timeoutOverdue()).isTrue();
    }

    /**
     * happy-2 同条件两查透传：全维度筛选（店铺 + 状态）与分页原样传
     * 给仓储（search 先、count 后），total 用 count、records 用
     * search（末页截断时 total 与当前页行数分离口径）；未到期 PAID
     * 行不标记（判定公式「不晚于判定时刻」反例分支锁）。
     */
    @Test
    @DisplayName("happy：筛选与分页原样透传 + 同条件两查编序（search → count）")
    void filterAndPagePassedThroughWithTwoQueries() {
        PlatformLogisticsViewFilter filter = new PlatformLogisticsViewFilter(SHOP_A, SubOrderStatus.PAID);
        PageQuery page = new PageQuery(2, 20);
        when(platformLogisticsViewRepository.search(same(filter), same(page)))
                .thenReturn(List.of(row(SUB_2, SubOrderStatus.PAID,
                        Instant.now().plus(Duration.ofHours(1)), null, null)));
        when(platformLogisticsViewRepository.count(same(filter))).thenReturn(3L);

        PageResult<PlatformLogisticsViewItem> result = service.list(filter, page);

        InOrder inOrder = inOrder(platformLogisticsViewRepository);
        inOrder.verify(platformLogisticsViewRepository).search(same(filter), same(page));
        inOrder.verify(platformLogisticsViewRepository).count(same(filter));
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).timeoutOverdue()).isFalse();
        assertThat(result.pageNum()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
    }

    // ---------- critical path ----------

    /**
     * critical-1 空结果：无任何子单/无命中 → 空列表 + total 0（非 null）。
     */
    @Test
    @DisplayName("critical：空列表（空表/无命中）→ 空列表 + total 0")
    void emptyResult() {
        PlatformLogisticsViewFilter filter = new PlatformLogisticsViewFilter(SHOP_B, null);
        PageQuery page = new PageQuery(1, 10);
        when(platformLogisticsViewRepository.search(same(filter), same(page)))
                .thenReturn(List.of());
        when(platformLogisticsViewRepository.count(same(filter))).thenReturn(0L);

        PageResult<PlatformLogisticsViewItem> result = service.list(filter, page);

        assertThat(result.total()).isZero();
        assertThat(result.records()).isEmpty();
        assertThat(result.pageNum()).isEqualTo(1);
    }

    /**
     * critical-2 分页边界：total > 当前页行数（末页/深页）→ records 只含
     * 当前页行、total 恒为全量命中数（口径分离）。
     */
    @Test
    @DisplayName("critical：末页截断——records 当前页行、total 全量")
    void lastPageTruncation() {
        PlatformLogisticsViewFilter filter = new PlatformLogisticsViewFilter(null, null);
        PageQuery page = new PageQuery(2, 2);
        when(platformLogisticsViewRepository.search(same(filter), same(page)))
                .thenReturn(List.of(row(SUB_2, SubOrderStatus.PAID,
                        Instant.now().minus(Duration.ofHours(2)), null, null)));
        when(platformLogisticsViewRepository.count(same(filter))).thenReturn(3L);

        PageResult<PlatformLogisticsViewItem> result = service.list(filter, page);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.records()).hasSize(1);
        assertThat(result.pageNum()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(2);
    }

    /**
     * critical-3 平台全量视角与无 deadline 数据面：双 null 筛选原样透传
     * （跨店铺全集），PAID 行无 deadline 记录 → 不标记不谎报。
     */
    @Test
    @DisplayName("critical：全平台视角透传 + PAID 无 deadline 不标记")
    void fullPlatformViewWithNoDeadlineRow() {
        Instant now = Instant.now();
        PlatformLogisticsViewFilter filter = new PlatformLogisticsViewFilter(null, null);
        PageQuery page = new PageQuery(1, 100);
        when(platformLogisticsViewRepository.search(same(filter), same(page)))
                .thenReturn(List.of(row(SUB_1, SubOrderStatus.PAID, null, null, null)));
        when(platformLogisticsViewRepository.count(same(filter))).thenReturn(1L);

        PageResult<PlatformLogisticsViewItem> result = service.list(filter, page);

        verify(platformLogisticsViewRepository).search(same(filter), same(page));
        PlatformLogisticsViewItem only = result.records().get(0);
        assertThat(only.timeoutAt()).isNull();
        assertThat(only.timeoutOverdue()).isFalse();
    }

    // ---------- fail path ----------

    /**
     * fail-1 行查询仓储异常透传：search 抛异常 → 服务原样透传（不吞
     * 不包装），且失败路径不触发计数查询（短路）。
     */
    @Test
    @DisplayName("fail：行查询仓储异常透传 + 计数短路")
    void repositorySearchFailurePropagates() {
        doThrow(new IllegalStateException("replica 查询失败"))
                .when(platformLogisticsViewRepository).search(anyFilter(), anyPage());

        assertThatThrownBy(() -> service.list(
                new PlatformLogisticsViewFilter(SHOP_A, null), new PageQuery(1, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replica 查询失败");
        verify(platformLogisticsViewRepository, never()).count(anyFilter());
    }

    /**
     * fail-2 计数仓储异常透传：count 抛异常 → 服务原样透传。
     */
    @Test
    @DisplayName("fail：计数仓储异常透传")
    void repositoryCountFailurePropagates() {
        when(platformLogisticsViewRepository.search(anyFilter(), anyPage())).thenReturn(List.of());
        doThrow(new IllegalStateException("replica 计数失败"))
                .when(platformLogisticsViewRepository).count(anyFilter());

        assertThatThrownBy(() -> service.list(
                new PlatformLogisticsViewFilter(null, null), new PageQuery(1, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replica 计数失败");
    }

    /**
     * fail-3 仓储违约 null 防御：search 返回 null（契约违约）→ 服务兜底
     * 空列表不 NPE（与分页结果 null 防御同纪律——空态显式建模）。
     */
    @Test
    @DisplayName("fail：仓储违约 null → 空列表兜底不 NPE")
    void nullSearchResultDefensive() {
        PlatformLogisticsViewFilter filter = new PlatformLogisticsViewFilter(null, null);
        PageQuery page = new PageQuery(1, 10);
        when(platformLogisticsViewRepository.search(same(filter), same(page))).thenReturn(null);
        when(platformLogisticsViewRepository.count(same(filter))).thenReturn(0L);

        PageResult<PlatformLogisticsViewItem> result = service.list(filter, page);

        assertThat(result.total()).isZero();
        assertThat(result.records()).isEmpty();
    }

    private static PlatformLogisticsViewFilter anyFilter() {
        return org.mockito.ArgumentMatchers.any(PlatformLogisticsViewFilter.class);
    }

    private static PageQuery anyPage() {
        return org.mockito.ArgumentMatchers.any(PageQuery.class);
    }
}