package com.nona.inf.order;

import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.inf.timeout.TimeoutTask;
import com.nona.inf.timeout.TimeoutType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收货超时数据端口场景测试（ORDER_RECEIVE：sub_order 表 deadline 列承载
 * 面，契约）：
 * <p>
 * happy——findDue 经仓储超时扫描面按预期态 SHIPPED 过滤并映射候选
 * （id=sub_order 主键 / target=子订单 ID，id 与 target 同值）；claim 条件性
 * 认领（透传预期态复查）；clearDeadline 成功闭环；critical——claim 条件
 * 不满足 false 透传、findDue 无候选空列表、目标参数透传正确；fail——仓储
 * 扫描/认领异常原样透传。
 * <p>
 * 装配纪律：端口为普通类（既定装配纪律：
 * 构造器注入仓储 mock（@BeforeEach 重建，禁字段初始化）；桩纪律——
 * 以 lenient 豁免 UOE 挡道面，已按本文件断言面逐桩收回精确桩
 * （零豁免）。
 * <p>
 * 时间断言：now 为测试固定时刻（fixture 输入，非断言魔法值）；全部断言
 * 以同一 now 变量相对比较（零绝对日期魔法值）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class ReceiveTimeoutStoreUnitTest {

    /**
     * 到期子订单主键（id 与 target 同值：收货超时操作单元即子单）
     */
    private static final long SUB_ORDER_ID = 720L;

    /**
     * 扫描时刻 fixture（findDue 透传断言基准；全部断言相对本变量）
     */
    private static final Instant NOW = Instant.parse("2026-09-08T12:30:00Z");

    /**
     * 单轮扫描上限（引擎 SCAN_LIMIT 语义透传）
     */
    private static final int LIMIT = 100;

    @Mock
    private SubOrderRepository subOrderRepository;

    /**
     * 被测数据端口（setUp 重建）
     */
    private ReceiveTimeoutStore store;

    /**
     * 每用例前重建被测端口（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        store = new ReceiveTimeoutStore(subOrderRepository);
    }

    /* ================= fixtures ================= */

    /**
     * 到期待收货子单（形态自洽：金额 = 条目快照，避免构造断言；deadline
     * 语义由仓库侧承接，本端口对实体形态无感知——映射键仅 id/target）。
     */
    private static SubOrder dueSubOrder() {
        return new SubOrder(SUB_ORDER_ID, 100L, 4001L, "SO202609080002",
                address(), amount(35_000L), List.of(item()));
    }

    private static AmountDetail amount(long goods) {
        return new AmountDetail(goods, 0L, 0L, goods);
    }

    private static OrderItem item() {
        return new OrderItem(90002L, 90002L, "测试商品", 35_000L, 1,
                35_000L, null, null, Map.of(), Map.of());
    }

    private static com.nona.domain.order.entity.AddressSnapshot address() {
        return new com.nona.domain.order.entity.AddressSnapshot(
                "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /* ================= happy path ================= */

    @Test
    @DisplayName("happy-1 type 注册：数据端口声明 ORDER_RECEIVE（引擎按类型路由）")
    void type_isOrderReceive() {
        assertThat(store.type()).isEqualTo(TimeoutType.ORDER_RECEIVE);
    }

    @Test
    @DisplayName("happy-2 findDue 扫描：预期态 SHIPPED + 截止时刻透传，候选映射 id=target=子订单 ID")
    void findDue_forwardsScanWithExpectedStatus() {
        when(subOrderRepository.findDueByStatusAndTimeoutAtBefore(
                        eq(SubOrderStatus.SHIPPED), eq(NOW), eq(LIMIT)))
                .thenReturn(java.util.List.of(dueSubOrder()));

        final java.util.List<TimeoutTask<Long>> due = store.findDue(NOW, LIMIT);

        assertThat(due).containsExactly(new TimeoutTask<>(SUB_ORDER_ID, SUB_ORDER_ID));
        verify(subOrderRepository).findDueByStatusAndTimeoutAtBefore(
                SubOrderStatus.SHIPPED, NOW, LIMIT);
    }

    @Test
    @DisplayName("happy-3 claim 认领：条件 UPDATE 成功（affected=1）→ true 透传（引擎才 fire）")
    void claim_successWhenAffected() {
        when(subOrderRepository.claimTimeout(SUB_ORDER_ID, SubOrderStatus.SHIPPED))
                .thenReturn(true);

        assertThat(store.claim(new TimeoutTask<>(SUB_ORDER_ID, SUB_ORDER_ID))).isTrue();
        verify(subOrderRepository).claimTimeout(SUB_ORDER_ID, SubOrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("happy-4 clearDeadline 闭环：处理成功后清理截止时间与认领位（p2-9 语义）")
    void clearDeadline_forwardsById() {
        store.clearDeadline(new TimeoutTask<>(SUB_ORDER_ID, SUB_ORDER_ID));

        verify(subOrderRepository).clearTimeoutDeadline(SUB_ORDER_ID);
    }

    /* ================= critical path ================= */

    @Test
    @DisplayName("critical-1 claim 条件不满足（他方已认领/状态已迁移）→ false 透传，引擎跳过不 fire")
    void claim_falseWhenNotAffected() {
        when(subOrderRepository.claimTimeout(SUB_ORDER_ID, SubOrderStatus.SHIPPED))
                .thenReturn(false);

        assertThat(store.claim(new TimeoutTask<>(SUB_ORDER_ID, SUB_ORDER_ID))).isFalse();
    }

    @Test
    @DisplayName("critical-2 findDue 无到期候选 → 空列表（引擎空转零操作）")
    void findDue_emptyWhenNoCandidates() {
        when(subOrderRepository.findDueByStatusAndTimeoutAtBefore(
                        eq(SubOrderStatus.SHIPPED), eq(NOW), eq(LIMIT)))
                .thenReturn(java.util.List.of());

        assertThat(store.findDue(NOW, LIMIT)).isEmpty();
    }

    /* ================= fail path ================= */

    @Test
    @DisplayName("fail-1 仓储扫描异常原样透传（引擎侧捕获记录，下轮重扫）")
    void findDue_propagatesRepositoryException() {
        final RuntimeException boom = new IllegalStateException("扫描失败");
        when(subOrderRepository.findDueByStatusAndTimeoutAtBefore(
                        eq(SubOrderStatus.SHIPPED), eq(NOW), eq(LIMIT)))
                .thenThrow(boom);

        assertThatThrownBy(() -> store.findDue(NOW, LIMIT)).isSameAs(boom);
    }

    @Test
    @DisplayName("fail-2 仓储认领异常原样透传（claim 抛错 → 事务回滚，下轮重扫再认领）")
    void claim_propagatesRepositoryException() {
        final RuntimeException boom = new IllegalStateException("认领失败");
        when(subOrderRepository.claimTimeout(anyLong(),
                        org.mockito.ArgumentMatchers.any(SubOrderStatus.class)))
                .thenThrow(boom);

        assertThatThrownBy(() -> store.claim(new TimeoutTask<>(SUB_ORDER_ID, SUB_ORDER_ID)))
                .isSameAs(boom);
    }
}