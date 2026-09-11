package com.nona.inf.persistence.repository;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.exceptions.BusinessException;
import com.nona.inf.persistence.converters.WaybillConvertor;
import com.nona.inf.persistence.po.logistics.WaybillPO;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import com.nona.inf.persistence.repository.jpa.WaybillJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillTrackJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运单仓储实现场景测试（契约：在途锚点 / 在途全量扫描 /
 * 按子单装载面 / 级联删）。
 * <p>
 * happy——findInTransitBySubOrderId 命中在途、findInTransit 在途全量、
 * findBySubOrderId 含签收终态最新行、级联删轨迹先删；critical——
 * 无在途/无装载均空（fail-safe）、在途扫描含坏行（无轨迹可装载）时
 * 逐条容错跳过不毒化整轮；fail——删除不存在返回 0。
 * <p>
 * 装配纪律：依赖全 mock，无容器；被测仓储 @BeforeEach 重建；行为桩
 * lenient 豁免 UOE 挡道（按需收回精确桩）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class WaybillRepositoryImplUnitTest {

    /**
     * 子单 ID（在途锚点/按子单装载业务键）
     */
    private static final long SUB_ORDER_ID = 6666L;

    @Mock
    private WaybillJpaRepository waybillJpaRepository;

    @Mock
    private WaybillConvertor convertor;

    @Mock
    private ChangeTrackerProvider changeTrackerProvider;

    @Mock
    private WaybillTrackJpaRepository trackJpaRepository;

    /**
     * 被测仓储（setUp 重建）
     */
    private WaybillRepositoryImpl repository;

    /**
     * 每用例前重建被测仓储（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        repository = new WaybillRepositoryImpl(waybillJpaRepository, convertor,
                changeTrackerProvider, trackJpaRepository);
    }

    @Test
    @DisplayName("happy：在途锚点命中（「一子单一在途」反查，发货编排查重面）")
    void findInTransitBySubOrderId_inTransitExists_returnsWaybill() {
        final WaybillPO po = new WaybillPO();
        when(waybillJpaRepository.findBySubOrderIdAndInTransitTrue(SUB_ORDER_ID))
                .thenReturn(Optional.of(po));
        final Waybill waybill = org.mockito.Mockito.mock(Waybill.class);
        when(convertor.convertToRoot(any(WaybillPO.class), any()))
                .thenReturn(waybill);
        when(trackJpaRepository.findByWaybillIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final Optional<Waybill> result = repository.findInTransitBySubOrderId(SUB_ORDER_ID);

        assertThat(result).containsSame(waybill);
    }

    @Test
    @DisplayName("fail：无在途运单返回空（不变量锚点的空语义）")
    void findInTransitBySubOrderId_absent_returnsEmpty() {
        when(waybillJpaRepository.findBySubOrderIdAndInTransitTrue(SUB_ORDER_ID))
                .thenReturn(Optional.empty());

        assertThat(repository.findInTransitBySubOrderId(SUB_ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("happy：在途全量扫描返回全部未签收运单")
    void findInTransit_returnsAllInTransit() {
        final WaybillPO first = new WaybillPO();
        final WaybillPO second = new WaybillPO();
        when(waybillJpaRepository.findByInTransitTrue()).thenReturn(List.of(first, second));
        final Waybill waybill = org.mockito.Mockito.mock(Waybill.class);
        when(convertor.convertToRoot(any(WaybillPO.class), any()))
                .thenReturn(waybill);
        when(trackJpaRepository.findByWaybillIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final List<Waybill> result = repository.findInTransit();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("critical：无在途运单返回空列表（扫描面 fail-safe）")
    void findInTransit_noInTransit_returnsEmpty() {
        when(waybillJpaRepository.findByInTransitTrue()).thenReturn(List.of());

        assertThat(repository.findInTransit()).isEmpty();
    }

    @Test
    @DisplayName("critical：在途扫描含坏行（无轨迹可装载）时逐条容错跳过，可装载行照常返回——扫描面不被脏数据整体毒化")
    void findInTransit_unloadableRowSkipped_loadableRowsReturned() {
        final WaybillPO bad = new WaybillPO();
        bad.setId(1000012L);
        final WaybillPO good = new WaybillPO();
        good.setId(1000013L);
        when(waybillJpaRepository.findByInTransitTrue()).thenReturn(List.of(bad, good));
        final Waybill waybill = org.mockito.Mockito.mock(Waybill.class);
        // 坏行形态：in_transit 位残留但轨迹缺失 → 聚合装载守卫拒绝（BusinessAssert 抛 BusinessException）
        when(convertor.convertToRoot(eq(bad), any()))
                .thenThrow(new BusinessException("logistics.waybill_invalid",
                        "运单轨迹列表不能为空（至少一条初始轨迹）"));
        when(convertor.convertToRoot(eq(good), any())).thenReturn(waybill);
        when(trackJpaRepository.findByWaybillIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final List<Waybill> result = repository.findInTransit();

        // 坏行被跳过，可装载行不受影响——扫描推进器仍可处理本轮其余运单
        assertThat(result).containsExactly(waybill);
    }

    @Test
    @DisplayName("happy：按子单装载命中（含签收终态，历史并存行取最新）")
    void findBySubOrderId_match_returnsWaybill() {
        final WaybillPO po = new WaybillPO();
        when(waybillJpaRepository.findFirstBySubOrderIdOrderByIdDesc(SUB_ORDER_ID))
                .thenReturn(Optional.of(po));
        final Waybill waybill = org.mockito.Mockito.mock(Waybill.class);
        when(convertor.convertToRoot(any(WaybillPO.class), any()))
                .thenReturn(waybill);
        when(trackJpaRepository.findByWaybillIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final Optional<Waybill> result = repository.findBySubOrderId(SUB_ORDER_ID);

        assertThat(result).containsSame(waybill);
        verify(waybillJpaRepository).findFirstBySubOrderIdOrderByIdDesc(SUB_ORDER_ID);
    }

    @Test
    @DisplayName("fail：按子单无运单返回空（详情面空态语义）")
    void findBySubOrderId_absent_returnsEmpty() {
        when(waybillJpaRepository.findFirstBySubOrderIdOrderByIdDesc(SUB_ORDER_ID))
                .thenReturn(Optional.empty());

        assertThat(repository.findBySubOrderId(SUB_ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("happy：级联删——轨迹行先删、根行后删，返回真实受影响行数 1")
    void deleteByID_cascadesTracksThenRoot_returnsAffectedOne() {
        when(waybillJpaRepository.existsById(anyLong())).thenReturn(true);

        assertThat(repository.deleteByID(7501L)).isEqualTo(1);
        verify(trackJpaRepository).deleteByWaybillId(7501L);
        verify(waybillJpaRepository).deleteById(7501L);
    }

    @Test
    @DisplayName("fail：删除不存在的根行返回 0（真实语义，非契约形）")
    void deleteByID_rootAbsent_returnsZero() {
        when(waybillJpaRepository.existsById(anyLong())).thenReturn(false);

        assertThat(repository.deleteByID(7502L)).isEqualTo(0);
    }
}