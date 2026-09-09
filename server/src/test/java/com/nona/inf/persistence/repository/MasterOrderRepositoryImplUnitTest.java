package com.nona.inf.persistence.repository;

import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.inf.persistence.converters.MasterOrderConvertor;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.repository.jpa.MasterOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主订单仓储实现场景测试（WU-55 红阶段契约：查询契约扩展 + 真实删除
 * 语义）：
 * <p>
 * happy——买家分页按状态多值过滤（含空集合=全量分支）、count 透传、
 * deleteByID 返回真实受影响行数；critical——非法分页参数（limit=0/
 * offset&lt;0）fail-closed 拒绝、超界空页 fail-safe；fail——状态过滤
 * 无命中返回空列表、目标不存在删除返回 0。
 * <p>
 * 装配纪律：依赖全 mock（主表 JPA/子单反查 JPA/转换器/追踪提供者），
 * 无容器；被测仓储 @BeforeEach 重建（禁字段初始化 new X(mock)）。
 * 桩纪律（红阶段实证先例 ShipTimeoutStoreUnitTest）：行为桩以 lenient
 * 豁免 UOE 挡道面（红阶段 UOE 先于桩消费抛出），绿实现后逐桩收回为
 * 精确桩（零豁免）。全部断言为绿阶段可转绿的「真实行为」断言——
 * 现红的原因是 {@link UnsupportedOperationException}（实现缺失），
 * 非语法/装配错误。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class MasterOrderRepositoryImplUnitTest {

    /**
     * 买家 ID fixture（分页定位业务键）
     */
    private static final long BUYER_ID = 9001L;

    @Mock
    private MasterOrderJpaRepository masterOrderJpaRepository;

    @Mock
    private SubOrderJpaRepository subOrderJpaRepository;

    @Mock
    private MasterOrderConvertor convertor;

    @Mock
    private ChangeTrackerProvider changeTrackerProvider;

    /**
     * 被测仓储（setUp 重建）
     */
    private MasterOrderRepositoryImpl repository;

    /**
     * 每用例前重建被测仓储（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        repository = new MasterOrderRepositoryImpl(masterOrderJpaRepository, convertor,
                changeTrackerProvider, subOrderJpaRepository);
    }

    @Test
    @DisplayName("happy：买家分页按状态多值过滤返回映射列表")
    void listPagedByBuyer_statusFiltered_returnsMappedList() {
        final MasterOrderPO first = new MasterOrderPO();
        final MasterOrderPO second = new MasterOrderPO();
        final MasterOrder root = org.mockito.Mockito.mock(MasterOrder.class);
        when(masterOrderJpaRepository.findByBuyerIdAndStatusInOrderByCreateTimeDescIdDesc(
                eq(BUYER_ID), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(first, second)));
        when(convertor.convertToRoot(any(MasterOrderPO.class), any()))
                .thenReturn(root);

        final List<MasterOrder> result =
                repository.listPagedByBuyer(BUYER_ID, Set.of(MasterOrderStatus.PAID), 0, 20);

        assertThat(result).hasSize(2);
        verify(masterOrderJpaRepository)
                .findByBuyerIdAndStatusInOrderByCreateTimeDescIdDesc(
                        eq(BUYER_ID), eq(Set.of(MasterOrderStatus.PAID)),
                        any(PageRequest.class));
    }

    @Test
    @DisplayName("critical：状态空集合=不过滤（「全部」tab 承载面，走全量分支）")
    void listPagedByBuyer_statusEmpty_noFilterBranch() {
        final MasterOrderPO po = new MasterOrderPO();
        final MasterOrder root = org.mockito.Mockito.mock(MasterOrder.class);
        when(masterOrderJpaRepository.findByBuyerIdOrderByCreateTimeDescIdDesc(
                eq(BUYER_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(po)));
        when(convertor.convertToRoot(any(MasterOrderPO.class), any()))
                .thenReturn(root);

        final List<MasterOrder> result =
                repository.listPagedByBuyer(BUYER_ID, List.of(), 0, 20);

        assertThat(result).hasSize(1);
        verify(masterOrderJpaRepository)
                .findByBuyerIdOrderByCreateTimeDescIdDesc(eq(BUYER_ID), any(PageRequest.class));
    }

    @Test
    @DisplayName("critical：limit=0 非法分页参数 fail-closed 拒绝（IAE）")
    void listPagedByBuyer_limitZero_rejected() {
        assertThatThrownBy(() -> repository.listPagedByBuyer(BUYER_ID, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("critical：offset<0 非法分页参数 fail-closed 拒绝（IAE）")
    void listPagedByBuyer_offsetNegative_rejected() {
        assertThatThrownBy(() -> repository.listPagedByBuyer(BUYER_ID, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("fail：状态过滤无命中返回空列表（fail-safe）")
    void listPagedByBuyer_noMatch_returnsEmpty() {
        when(masterOrderJpaRepository.findByBuyerIdAndStatusInOrderByCreateTimeDescIdDesc(
                eq(BUYER_ID), any(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        final List<MasterOrder> result =
                repository.listPagedByBuyer(BUYER_ID, Set.of(MasterOrderStatus.CLOSED), 0, 20);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("happy：count 按买家+状态过滤条件透传")
    void countByBuyer_filtered_returnsTotal() {
        when(masterOrderJpaRepository.countByBuyerIdAndStatusIn(eq(BUYER_ID), any()))
                .thenReturn(7L);

        assertThat(repository.countByBuyer(BUYER_ID, Set.of(MasterOrderStatus.COMPLETED)))
                .isEqualTo(7L);
        verify(masterOrderJpaRepository).countByBuyerIdAndStatusIn(
                eq(BUYER_ID), eq(Set.of(MasterOrderStatus.COMPLETED)));
    }

    @Test
    @DisplayName("happy：删除主表行返回真实受影响行数（0/1）")
    void deleteByID_rootExists_returnsAffectedOne() {
        when(masterOrderJpaRepository.existsById(anyLong())).thenReturn(true);

        assertThat(repository.deleteByID(8801L)).isEqualTo(1);
        verify(masterOrderJpaRepository).deleteById(8801L);
    }

    @Test
    @DisplayName("fail：删除不存在的根行返回 0（真实语义，非契约形）")
    void deleteByID_rootAbsent_returnsZero() {
        when(masterOrderJpaRepository.existsById(anyLong())).thenReturn(false);

        assertThat(repository.deleteByID(8802L)).isEqualTo(0);
    }

    @Test
    @DisplayName("fail：null 删除委托按无操作返回 0")
    void delete_null_returnsZero() {
        assertThat(repository.delete(null)).isEqualTo(0);
    }
}