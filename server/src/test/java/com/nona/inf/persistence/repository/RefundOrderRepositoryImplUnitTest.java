package com.nona.inf.persistence.repository;

import com.nona.domain.payment.entity.RefundOrder;
import com.nona.inf.persistence.converters.RefundOrderConvertor;
import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import com.nona.inf.persistence.po.payment.RefundOrderPO;
import com.nona.inf.persistence.repository.jpa.RefundCallbackLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.RefundOrderJpaRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款单仓储实现场景测试（WU-55 红阶段契约：退款回调装载锚点对 /
 * 申请防重锚点 / 级联删）。
 * <p>
 * happy——findByRefundNo/findBySubOrderId 装载命中、级联删留痕先删；
 * fail——装载不存在均按 null 呈现（孤儿退款回调不产生处理路径、防重
 * 按「新建」处理——接口 javadoc 冻结语义）、删除不存在返回 0。
 * <p>
 * 装配纪律：依赖全 mock，无容器；被测仓储 @BeforeEach 重建；行为桩
 * lenient 豁免 UOE 挡道（绿实现后收回精确桩）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class RefundOrderRepositoryImplUnitTest {

    /**
     * 退款单号（退款回调装载锚点业务键）
     */
    private static final String REFUND_NO = "RF202609080000000001";

    /**
     * 操作单元子单 ID（申请防重锚点业务键）
     */
    private static final long SUB_ORDER_ID = 5555L;

    @Mock
    private RefundOrderJpaRepository refundOrderJpaRepository;

    @Mock
    private RefundOrderConvertor convertor;

    @Mock
    private ChangeTrackerProvider changeTrackerProvider;

    @Mock
    private RefundCallbackLogJpaRepository callbackLogJpaRepository;

    /**
     * 被测仓储（setUp 重建）
     */
    private RefundOrderRepositoryImpl repository;

    /**
     * 每用例前重建被测仓储（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        repository = new RefundOrderRepositoryImpl(refundOrderJpaRepository, convertor,
                changeTrackerProvider, callbackLogJpaRepository);
    }

    @Test
    @DisplayName("happy：按退款单号装载命中（退款回调处理锚点）")
    void findByRefundNo_match_returnsOrder() {
        final RefundOrderPO po = new RefundOrderPO();
        when(refundOrderJpaRepository.findByRefundNo(REFUND_NO)).thenReturn(Optional.of(po));
        final RefundOrder order = org.mockito.Mockito.mock(RefundOrder.class);
        when(convertor.convertToRoot(any(RefundOrderPO.class), any()))
                .thenReturn(order);
        when(callbackLogJpaRepository.findByRefundOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final RefundOrder result = repository.findByRefundNo(REFUND_NO);

        assertThat(result).isSameAs(order);
    }

    @Test
    @DisplayName("fail：按退款单号装载不存在返回 null（孤儿回调不产生处理路径）")
    void findByRefundNo_absent_returnsNull() {
        when(refundOrderJpaRepository.findByRefundNo(REFUND_NO)).thenReturn(Optional.empty());

        assertThat(repository.findByRefundNo(REFUND_NO)).isNull();
    }

    @Test
    @DisplayName("happy：按子单装载命中（申请防重/超时短路锚点）")
    void findBySubOrderId_match_returnsOrder() {
        final RefundOrderPO po = new RefundOrderPO();
        when(refundOrderJpaRepository.findBySubOrderId(SUB_ORDER_ID))
                .thenReturn(Optional.of(po));
        final RefundOrder order = org.mockito.Mockito.mock(RefundOrder.class);
        when(convertor.convertToRoot(any(RefundOrderPO.class), any()))
                .thenReturn(order);
        when(callbackLogJpaRepository.findByRefundOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of());

        final RefundOrder result = repository.findBySubOrderId(SUB_ORDER_ID);

        assertThat(result).isSameAs(order);
    }

    @Test
    @DisplayName("fail：按子单装载不存在返回 null（防重按「新建」处理）")
    void findBySubOrderId_absent_returnsNull() {
        when(refundOrderJpaRepository.findBySubOrderId(SUB_ORDER_ID))
                .thenReturn(Optional.empty());

        assertThat(repository.findBySubOrderId(SUB_ORDER_ID)).isNull();
    }

    @Test
    @DisplayName("happy：级联删——留痕行先删、根行后删，返回真实受影响行数 1")
    void deleteByID_cascadesLogsThenRoot_returnsAffectedOne() {
        final RefundCallbackLogPO log = new RefundCallbackLogPO();
        when(callbackLogJpaRepository.findByRefundOrderIdOrderByIdAsc(any()))
                .thenReturn(List.of(log));
        when(refundOrderJpaRepository.existsById(anyLong())).thenReturn(true);

        assertThat(repository.deleteByID(6601L)).isEqualTo(1);
        verify(callbackLogJpaRepository).deleteAll(List.of(log));
        verify(refundOrderJpaRepository).deleteById(6601L);
    }

    @Test
    @DisplayName("fail：删除不存在的根行返回 0（真实语义，非契约形）")
    void deleteByID_rootAbsent_returnsZero() {
        when(refundOrderJpaRepository.existsById(anyLong())).thenReturn(false);

        assertThat(repository.deleteByID(6602L)).isEqualTo(0);
    }
}