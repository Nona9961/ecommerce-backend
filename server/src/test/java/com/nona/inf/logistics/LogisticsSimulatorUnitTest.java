package com.nona.inf.logistics;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.logistics.factory.WaybillFactory;
import com.nona.domain.logistics.ports.WaybillDelivered;
import com.nona.domain.logistics.ports.WaybillDeliveredPublisher;
import com.nona.domain.logistics.repo.WaybillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物流模拟推进器场景测试（模拟物流状态机自动推进——待发货→已发货→
 * 运输中→已签收契约）：
 * <p>
 * happy——待发货无延时直推已发货 / 已发货满 30 秒推运输中 / 运输中满
 * 60 秒推已签收并发签收事件（载荷 = 运单 + 关联子单）/ 混合批次逐单
 * 推进；critical——未到期不推进 / 30 秒整到期边界 / 已签收终态防御
 * 跳过（不推进不重复发布）/ 单条推进独立事务；fail——单条推进失败
 * 告警续批、发布失败批次继续、扫描装载异常透传。
 * <p>
 * 装配纪律：时间源注入固定时钟（推进判定时刻 = 固定扫描时刻），轨迹
 * 时点断言从扫描时刻相对派生（零绝对日期魔法值）；Waybill fixture 与
 * 断言用同一实例（运单无 equals/hashCode，stub/verify 复用局部实例，
 * 不做跨实例匹配）；推进事务以 mock 直执行（事务边界属装配面，绿期
 * 冒烟测试验证真实独立提交）。
 * <p>
 * 桩纪律：UOE 挡道（scanAndAdvance/isDue 未接线），全部桩以
 * lenient 豁免（按本文件断言
 * 面逐用例收回为精确桩）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class LogisticsSimulatorUnitTest {

    /**
     * 测试运单/子单标识
     */
    private static final long WAYBILL_1 = 9001L;
    private static final long WAYBILL_2 = 9002L;
    private static final long SUB_1 = 101L;
    private static final long SUB_2 = 102L;
    private static final long TRACK_BASE = 7000L;

    /**
     * 承运公司与运单号（运单形态自洽 fixture）
     */
    private static final String COMPANY = "顺丰速运";
    private static final String TRACKING_NO = "SF202609080001";

    /**
     * 固定扫描时刻（时钟 fixture 输入；全部时间断言从本时刻相对派生）
     */
    private static final Instant SCAN = Instant.parse("2026-09-08T12:00:00Z");

    /**
     * 固定时钟时区（UTC 对齐——轨迹时点断言经本时区换算）
     */
    private static final ZoneId SCAN_ZONE = ZoneId.of("UTC");

    /**
     * 扫描时刻的本地时点（期望断言基准：推进器把扫描时刻传工厂定型
     * 轨迹发生时间）
     */
    private static LocalDateTime scanLocal() {
        return LocalDateTime.ofInstant(SCAN, SCAN_ZONE);
    }

    @Mock
    private WaybillRepository waybillRepository;

    @Mock
    private WaybillFactory waybillFactory;

    @Mock
    private WaybillDeliveredPublisher waybillDeliveredPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    /**
     * 被测推进器（固定时钟 + 默认节奏 30s/60s，setUp 装配）
     */
    private LogisticsSimulator simulator;

    /**
     * 每用例前重建被测推进器（mock 桩在各用例内布置，UOE 挡道面
     * lenient 豁免）。
     */
    @BeforeEach
    void setUp() {
        simulator = new LogisticsSimulator(waybillRepository, waybillFactory,
                waybillDeliveredPublisher, transactionTemplate,
                Duration.ofSeconds(30), Duration.ofSeconds(60),
                Clock.fixed(SCAN, SCAN_ZONE));
        lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(transactionStatus));
        lenient().when(waybillFactory.createTrack(any(Waybill.class), any(WaybillStatus.class),
                        any(LocalDateTime.class), any(String.class)))
                .thenAnswer(invocation -> trackFor(
                        invocation.getArgument(0, Waybill.class),
                        invocation.getArgument(1, WaybillStatus.class),
                        invocation.getArgument(2, LocalDateTime.class),
                        invocation.getArgument(3, String.class)));
    }

    /* ================= fixtures ================= */

    /**
     * 轨迹装配（归属运单/状态/时刻/描述——append-only 形态自洽）。
     */
    private static WaybillTrack trackFor(Waybill waybill, WaybillStatus status,
                                         LocalDateTime at, String desc) {
        return new WaybillTrack(TRACK_BASE + waybill.getId() * 10 + status.ordinal(),
                waybill.getId(), status, at, desc);
    }

    /**
     * 按状态与末条轨迹时点装配合法运单（形态自洽：末条轨迹状态 ==
     * 当前状态；轨迹时间线含创建节点与推进节点）。
     */
    private static Waybill waybillAt(long waybillId, long subOrderId,
                                     WaybillStatus status, LocalDateTime lastTrackAt) {
        final List<WaybillTrack> tracks = status == WaybillStatus.PENDING_SHIPMENT
                ? List.of(new WaybillTrack(TRACK_BASE + 1, waybillId,
                WaybillStatus.PENDING_SHIPMENT, lastTrackAt, "运单创建，等待发货"))
                : List.of(
                new WaybillTrack(TRACK_BASE + 1, waybillId,
                        WaybillStatus.PENDING_SHIPMENT, lastTrackAt.minusSeconds(90),
                        "运单创建，等待发货"),
                new WaybillTrack(TRACK_BASE + 2, waybillId, status, lastTrackAt,
                        status == WaybillStatus.SHIPPED ? "货物已发出（模拟推进）"
                                : status == WaybillStatus.IN_TRANSIT ? "运输中（模拟推进）"
                                : "已签收（模拟推进）"));
        return new Waybill(waybillId, subOrderId, COMPANY, TRACKING_NO, status, tracks);
    }

    /**
     * 待发货运单（创建于扫描时刻前 5 秒——无延时直推面）。
     */
    private static Waybill wPending() {
        return waybillAt(WAYBILL_1, SUB_1, WaybillStatus.PENDING_SHIPMENT,
                scanLocal().minusSeconds(5));
    }

    /**
     * 已发货到期运单（满 30 秒——应推运输中）。
     */
    private static Waybill wShippedDue() {
        return waybillAt(WAYBILL_1, SUB_1, WaybillStatus.SHIPPED,
                scanLocal().minusSeconds(35));
    }

    /**
     * 已发货未到期运单（29 秒——不推）。
     */
    private static Waybill wShippedNotDue() {
        return waybillAt(WAYBILL_1, SUB_1, WaybillStatus.SHIPPED,
                scanLocal().minusSeconds(29));
    }

    /**
     * 已发货边界到期运单（恰满 30 秒——到期判定含边界）。
     */
    private static Waybill wShippedBoundary() {
        return waybillAt(WAYBILL_1, SUB_1, WaybillStatus.SHIPPED,
                scanLocal().minusSeconds(30));
    }

    /**
     * 运输中到期运单（满 60 秒——应推已签收并发布事件）。
     */
    private static Waybill wInTransitDue() {
        return waybillAt(WAYBILL_2, SUB_2, WaybillStatus.IN_TRANSIT,
                scanLocal().minusSeconds(65));
    }

    /**
     * 运输中未到期运单（59 秒——不推）。
     */
    private static Waybill wInTransitNotDue() {
        return waybillAt(WAYBILL_2, SUB_2, WaybillStatus.IN_TRANSIT,
                scanLocal().minusSeconds(59));
    }

    /**
     * 已签收终态运单（防御跳过面——不应出现在在途扫描结果，但防线
     * 不推进不重复发布）。
     */
    private static Waybill wDelivered() {
        return waybillAt(WAYBILL_2, SUB_2, WaybillStatus.DELIVERED,
                scanLocal().minusSeconds(120));
    }

    /* ================= happy path ================= */

    /**
     * happy-1 待发货无延时直推：扫描即推已发货——轨迹追加（目标状态/
     * 扫描时刻/推进描述）+ 状态前移落库，不发布事件。
     */
    @Test
    @DisplayName("待发货扫到即推已发货：轨迹追加 + 落库，不发布事件")
    void scanAndAdvance_pendingShipment_advancesToShipped() {
        final Waybill waybill = wPending();
        when(waybillRepository.findInTransit()).thenReturn(List.of(waybill));

        simulator.scanAndAdvance();

        verify(waybillFactory).createTrack(eq(waybill), eq(WaybillStatus.SHIPPED),
                eq(scanLocal()), eq(LogisticsSimulator.DESC_SHIPPED));
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.SHIPPED);
        assertThat(waybill.getTracks()).hasSize(2);
        verify(waybillRepository).save(waybill);
        verify(waybillDeliveredPublisher, never()).publishWaybillDelivered(any());
    }

    /**
     * happy-2 已发货满 30 秒推运输中：到期判定（末条轨迹时点起算）+
     * 轨迹追加 + 落库，签收事件未达不发布。
     */
    @Test
    @DisplayName("已发货满 30 秒推运输中（到期判定 + 轨迹追加 + 落库）")
    void scanAndAdvance_shippedDue_advancesToInTransit() {
        final Waybill waybill = wShippedDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(waybill));

        simulator.scanAndAdvance();

        verify(waybillFactory).createTrack(eq(waybill), eq(WaybillStatus.IN_TRANSIT),
                eq(scanLocal()), eq(LogisticsSimulator.DESC_IN_TRANSIT));
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.IN_TRANSIT);
        assertThat(waybill.getTracks()).hasSize(3);
        verify(waybillRepository).save(waybill);
        verify(waybillDeliveredPublisher, never()).publishWaybillDelivered(any());
    }

    /**
     * happy-3 运输中满 60 秒推已签收并发布签收事件：事件载荷 = 签收
     * 运单 + 关联子单定位引用；推进成功后才发布（同事务内，AFTER_
     * COMMIT 投递由监听侧表达）。
     */
    @Test
    @DisplayName("运输中满 60 秒推已签收并发布签收事件（载荷断言）")
    void scanAndAdvance_inTransitDue_deliveredAndEventPublished() {
        final Waybill waybill = wInTransitDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(waybill));

        simulator.scanAndAdvance();

        verify(waybillFactory).createTrack(eq(waybill), eq(WaybillStatus.DELIVERED),
                eq(scanLocal()), eq(LogisticsSimulator.DESC_DELIVERED));
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.DELIVERED);
        assertThat(waybill.isInTransit()).isFalse();
        verify(waybillRepository).save(waybill);
        final ArgumentCaptor<WaybillDelivered> captor = ArgumentCaptor.forClass(WaybillDelivered.class);
        verify(waybillDeliveredPublisher).publishWaybillDelivered(captor.capture());
        assertThat(captor.getValue().getPayload().waybillId()).isEqualTo(WAYBILL_2);
        assertThat(captor.getValue().getPayload().subOrderId()).isEqualTo(SUB_2);
        assertThat(captor.getValue().getType()).isEqualTo(WaybillDelivered.TYPE);
    }

    /**
     * happy-4 混合批次逐单推进：一轮扫描内各到期运单按自身状态分派
     * （已发货→运输中 + 运输中→已签收+事件），未到期运单不推进。
     */
    @Test
    @DisplayName("混合批次逐单推进：各自状态分派，未到期不推进")
    void scanAndAdvance_mixedBatch_advancesEachDue() {
        final Waybill shipped = wShippedDue();
        final Waybill inTransit = wInTransitDue();
        final Waybill notDue = wShippedNotDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(shipped, inTransit, notDue));

        simulator.scanAndAdvance();

        assertThat(shipped.getStatus()).isEqualTo(WaybillStatus.IN_TRANSIT);
        assertThat(inTransit.getStatus()).isEqualTo(WaybillStatus.DELIVERED);
        assertThat(notDue.getStatus()).isEqualTo(WaybillStatus.SHIPPED);
        verify(waybillRepository).save(shipped);
        verify(waybillRepository).save(inTransit);
        verify(waybillRepository, never()).save(notDue);
        verify(waybillDeliveredPublisher).publishWaybillDelivered(any());
    }

    /* ================= critical path ================= */

    /**
     * critical-1 未到期判定：已发货 29 秒不推（到期边界严格性——未满
     * 30 秒保持原状零动作）；运输中 59 秒同样不推。
     */
    @Test
    @DisplayName("未到期不推进：29 秒/59 秒保持原状零动作")
    void scanAndAdvance_notDue_noAdvance() {
        final Waybill shipped = wShippedNotDue();
        final Waybill inTransit = wInTransitNotDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(shipped, inTransit));

        simulator.scanAndAdvance();

        assertThat(simulator.isDue(shipped, SCAN)).isFalse();
        assertThat(simulator.isDue(inTransit, SCAN)).isFalse();
        verify(waybillRepository, never()).save(any());
        verify(waybillFactory, never()).createTrack(any(), any(), any(), any());
        verify(waybillDeliveredPublisher, never()).publishWaybillDelivered(any());
    }

    /**
     * critical-2 到期边界含整点：恰满 30 秒（elapsed == 30）应推进——
     * 「满 N 秒」语义为 elapsed ≥ N。
     */
    @Test
    @DisplayName("到期边界：恰满 30 秒推运输中（含边界）")
    void scanAndAdvance_boundaryExactlyDue_advances() {
        final Waybill waybill = wShippedBoundary();
        when(waybillRepository.findInTransit()).thenReturn(List.of(waybill));

        assertThat(simulator.isDue(waybill, SCAN)).isTrue();
        simulator.scanAndAdvance();

        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.IN_TRANSIT);
        verify(waybillRepository).save(waybill);
    }

    /**
     * critical-3 已签收终态防御：推进器对终态运单跳过——不推进、不
     * 重复发布签收事件（不重复语义的发布侧防线；正常面由仓储契约
     * 保证不在途，本面为脏扫描防御）。
     */
    @Test
    @DisplayName("已签收终态防御：跳过不推进不重复发布")
    void scanAndAdvance_deliveredTerminal_skipped() {
        final Waybill waybill = wDelivered();
        when(waybillRepository.findInTransit()).thenReturn(List.of(waybill));

        simulator.scanAndAdvance();

        assertThat(simulator.isDue(waybill, SCAN)).isFalse();
        assertThat(waybill.getStatus()).isEqualTo(WaybillStatus.DELIVERED);
        verify(waybillRepository, never()).save(any());
        verify(waybillDeliveredPublisher, never()).publishWaybillDelivered(any());
    }

    /**
     * critical-4 单条推进独立事务：一轮扫描逐条事务——每单推进各占
     * 一个事务边界（单条失败不污染批次，见 fail 路径）。
     */
    @Test
    @DisplayName("单条推进独立事务：每单一个事务边界")
    void scanAndAdvance_perWaybillTransaction() {
        final Waybill shipped = wShippedDue();
        final Waybill inTransit = wInTransitDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(shipped, inTransit));

        simulator.scanAndAdvance();

        verify(transactionTemplate, times(2)).execute(any(TransactionCallback.class));
    }

    /* ================= fail path ================= */

    /**
     * fail-1 单条推进失败告警续批：某运单推进异常（落库失败）→ 该条
     * 事务回滚语义由事务封装承载，推进器记录后继续下一条——批次不
     * 中断，下轮重扫按状态快照幂等重试。
     */
    @Test
    @DisplayName("单条推进失败：告警续批，其余运单不受影响")
    void scanAndAdvance_oneFails_batchContinues() {
        final Waybill failing = wShippedDue();
        final Waybill healthy = wInTransitDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(failing, healthy));
        doThrow(new IllegalStateException("运单落库失败")).when(waybillRepository).save(failing);

        simulator.scanAndAdvance();

        assertThat(healthy.getStatus()).isEqualTo(WaybillStatus.DELIVERED);
        verify(waybillRepository).save(healthy);
        verify(waybillDeliveredPublisher).publishWaybillDelivered(any());
    }

    /**
     * fail-2 签收事件发布失败：批次继续（发布失败同事务回滚——下轮
     * 重扫重新推进并再次发布，不静默丢失签收）。
     */
    @Test
    @DisplayName("签收事件发布失败：批次继续（回滚语义由事务封装承载）")
    void scanAndAdvance_publishFails_batchContinues() {
        final Waybill failing = wInTransitDue();
        final Waybill healthy = wShippedDue();
        when(waybillRepository.findInTransit()).thenReturn(List.of(failing, healthy));
        doThrow(new IllegalStateException("事件发布失败")).when(waybillDeliveredPublisher)
                .publishWaybillDelivered(any());

        simulator.scanAndAdvance();

        assertThat(healthy.getStatus()).isEqualTo(WaybillStatus.IN_TRANSIT);
        verify(waybillRepository).save(healthy);
    }

    /**
     * fail-3 扫描装载异常透传：在途扫描仓储异常向调度框架层透传
     * （调度方负责记录，下轮周期自然重试——与超时调度引擎同构）。
     */
    @Test
    @DisplayName("扫描装载异常：向调度层透传（下轮周期重试）")
    void scanAndAdvance_scanFails_passedThrough() {
        when(waybillRepository.findInTransit())
                .thenThrow(new IllegalStateException("在途扫描失败"));

        assertThatThrownBy(() -> simulator.scanAndAdvance())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("在途扫描失败");
    }
}