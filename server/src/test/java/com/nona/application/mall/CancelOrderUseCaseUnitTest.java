package com.nona.application.mall;

import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.ports.OrderFacade;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantPrivilege;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消订单编排用例场景测试（主动取消 + 支付超时取消，
 * 契约测试）。
 * <p>
 * 覆盖：happy——待支付主单取消全链路（归属校验 → 订单取消 → 逐子单
 * 库存回滚 → 支付关单，reason 透传）与超时入口（reason=TIMEOUT）；
 * critical——已取消订单再取消幂等短路、支付单缺失跳过关单、多子单
 * 逐单回滚明细（订单项快照装配）；fail——主单不存在/归属不符 404、
 * 聚合守卫拒绝（已支付/已发货）异常透传且后续动作不发生。
 * <p>
 * 依赖装配：全部端口/仓储以 mock 承载（编排契约断言面）；提权事务
 * 以 mock 直执行（事务边界属应用层，由用例注解与提权包装承载，绿期
 * 集成测试验证真实回滚）。失败原因 = 实现缺失（用例方法体 UOE）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class CancelOrderUseCaseUnitTest {

    /**
     * 测试买家 / 主订单 / 店铺
     */
    private static final long BUYER_ID = 200L;
    private static final long MASTER_ID = 100L;
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;

    /**
     * 子单（A 店主单下 SKU 明细两项；B 店单 SKU）
     */
    private static final long SUB_A = 101L;
    private static final long SUB_B = 102L;
    private static final long SKU_A1 = 3001L;
    private static final long SKU_A2 = 3002L;
    private static final long SKU_B1 = 3101L;

    @Mock
    private MasterOrderRepository masterOrderRepository;

    @Mock
    private SubOrderRepository subOrderRepository;

    @Mock
    private OrderFacade orderFacade;

    @Mock
    private InventoryFacade inventoryFacade;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentPort paymentPort;

    @Mock
    private TenantPrivilege tenantPrivilege;

    @Mock
    private TransactionTemplate transactionTemplate;

    /**
     * 被测用例（不注册 Spring；依赖全 mock，setUp 装配）。
     */
    private CancelOrderUseCase useCase;

    /**
     * 每用例前重建被测用例（mock 桩在各用例内布置，桩全部被使用）。
     */
    @BeforeEach
    void setUp() {
        useCase = new CancelOrderUseCase(masterOrderRepository, subOrderRepository,
                orderFacade, inventoryFacade, paymentOrderRepository, paymentPort,
                tenantPrivilege, transactionTemplate);
        try {
            lenient().when(tenantPrivilege.elevatedInTransaction(eq(transactionTemplate), any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
    }

    /* ================= fixtures ================= */

    /**
     * 待支付主单基线（2 子单：A 店 35000+800，B 店 10000；主单合计
     * goods 45000 / freight 800 / paid 45800）。
     */
    private static MasterOrder pendingMaster() {
        return master(MASTER_ID, MasterOrderStatus.PENDING_PAYMENT);
    }

    /**
     * 按状态装配合法主单（金额恒等式以子单金额投影校验）。
     */
    private static MasterOrder master(Long id, MasterOrderStatus status) {
        return new MasterOrder(id, "ORD202609070001", BUYER_ID,
                address(), amount(45000L, 800L, 45800L),
                List.of(SUB_A, SUB_B),
                List.of(amount(35000L, 800L, 35800L), amount(10000L, 0L, 10000L)), status);
    }

    /**
     * 待支付子单 A（SKU_A1 × 2 + SKU_A2 × 3，goods 35000 freight 800）。
     */
    private static SubOrder subA() {
        return new SubOrder(SUB_A, MASTER_ID, SHOP_A, "SO202609070001", address(),
                amount(35000L, 800L, 35800L),
                List.of(item(SKU_A1, 10000L, 2), item(SKU_A2, 5000L, 3)));
    }

    /**
     * 待支付子单 B（SKU_B1 × 1，goods 10000 freight 0）。
     */
    private static SubOrder subB() {
        return new SubOrder(SUB_B, MASTER_ID, SHOP_B, "SO202609070002", address(),
                amount(10000L, 0L, 10000L),
                List.of(item(SKU_B1, 10000L, 1)));
    }

    private static OrderItem item(Long skuId, long price, int qty) {
        return new OrderItem(skuId, skuId, "测试商品" + skuId, price, qty, price * qty,
                null, null, Map.of(), Map.of());
    }

    private static AmountDetail amount(long goods, long freight, long paid) {
        return new AmountDetail(goods, freight, 0L, paid);
    }

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号");
    }

    /**
     * 关联主单的待支付支付单（payNo 断言锚点）。
     */
    private static PaymentOrder paymentOf(Long orderId) {
        return new PaymentOrder(9001L, "PAY20260907000001", orderId, 45800L, "MOCK",
                Instant.parse("2026-09-07T10:30:00Z"));
    }

    /* ================= happy path ================= */

    /**
     * happy-1 主动取消全链路：归属校验 → 订单取消（reason 透传）→ 逐子单
     * 库存回滚（明细 = 订单项快照）→ 支付关单（同事务编排序断言）。
     */
    @Test
    @DisplayName("待支付主单取消全链路：订单推进→逐子单回滚→支付关单（reason 透传）")
    void cancelByBuyer_pendingMaster_fullChain() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf(MASTER_ID));

        useCase.cancelByBuyer(BUYER_ID, MASTER_ID, "不想要了");

        final InOrder inOrder = inOrder(orderFacade, inventoryFacade, paymentPort);
        inOrder.verify(orderFacade).cancel(MASTER_ID, "不想要了");
        // 逐子单回滚：明细 = 订单项快照（SKU + 数量，与预占装配对称）
        inOrder.verify(inventoryFacade).rollback(SUB_A, List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3)));
        inOrder.verify(inventoryFacade).rollback(SUB_B, List.of(
                new StockChangeItem(SKU_B1, 1)));
        inOrder.verify(paymentPort).closePay("PAY20260907000001");
    }

    /**
     * happy-2 支付超时入口：reason = TIMEOUT 自动落位（超时调度
     * 复用面），编排与主动取消共用（订单取消 + 逐子单回滚 + 支付关单）。
     */
    @Test
    @DisplayName("支付超时取消：reason=TIMEOUT，编排与主动取消共用")
    void cancelByTimeout_pendingMaster_reasonTimeout() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA()));
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf(MASTER_ID));

        useCase.cancelByTimeout(MASTER_ID);

        verify(orderFacade).cancel(MASTER_ID, CancelOrderUseCase.REASON_TIMEOUT);
        verify(inventoryFacade).rollback(SUB_A, List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3)));
        verify(paymentPort).closePay("PAY20260907000001");
    }

    /**
     * happy-3 取消原因可空：买家不填原因传 null，编排透传订单门面
     * （签名契约允许，超时复用面不受影响）。
     */
    @Test
    @DisplayName("取消原因可空：null 透传订单门面")
    void cancelByBuyer_nullReason_passthrough() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA()));
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf(MASTER_ID));

        useCase.cancelByBuyer(BUYER_ID, MASTER_ID, null);

        verify(orderFacade).cancel(MASTER_ID, null);
        verify(inventoryFacade).rollback(anyLong(), anyList());
        verify(paymentPort).closePay("PAY20260907000001");
    }

    /* ================= critical path ================= */

    /**
     * critical-1 已取消订单再取消：主单已取消 → 幂等短路成功——不再触发
     * 订单推进/库存回滚/支付关单（主动取消重放与超时调度重扫常态路径）。
     */
    @Test
    @DisplayName("已取消订单再取消：幂等短路，无重复推进/回滚/关单")
    void cancelByBuyer_alreadyCancelled_idempotentShortCircuit() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(
                master(MASTER_ID, MasterOrderStatus.CANCELLED));

        useCase.cancelByBuyer(BUYER_ID, MASTER_ID, "重放");

        verify(orderFacade, never()).cancel(anyLong(), any());
        verify(inventoryFacade, never()).rollback(anyLong(), anyList());
        verify(paymentPort, never()).closePay(any());
        verify(paymentOrderRepository, never()).findByOrderId(anyLong());
    }

    /**
     * critical-2 超时重扫命中已取消订单：幂等短路成功（超时 handler
     * 幂等语义——closePay 对已关闭幂等 + 编排短路双重兜底）。
     */
    @Test
    @DisplayName("超时重扫已取消订单：幂等短路成功")
    void cancelByTimeout_alreadyCancelled_idempotent() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(
                master(MASTER_ID, MasterOrderStatus.CANCELLED));

        useCase.cancelByTimeout(MASTER_ID);

        verify(orderFacade, never()).cancel(anyLong(), any());
        verify(inventoryFacade, never()).rollback(anyLong(), anyList());
        verify(paymentPort, never()).closePay(any());
    }

    /**
     * critical-3 支付单缺失：跳过关单但订单取消与库存回滚照常（防御跳闸
     * ——主目标是订单+库存，缺支付单数据异常不阻塞取消，杜绝泄漏死锁）。
     */
    @Test
    @DisplayName("支付单缺失：取消与回滚成功，关单跳过")
    void cancelByBuyer_paymentMissing_skipsClosePay() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA()));
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(null);

        useCase.cancelByBuyer(BUYER_ID, MASTER_ID, "不想要了");

        verify(orderFacade).cancel(MASTER_ID, "不想要了");
        verify(inventoryFacade).rollback(SUB_A, List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3)));
        verify(paymentPort, never()).closePay(any());
    }

    /**
     * critical-4 跨店多子单：逐子单独立回滚（每子单以其订单项快照装配
     * 明细，预占对称面），A/B 店两次回滚调用互不合并。
     */
    @Test
    @DisplayName("跨店多子单：逐子单独立回滚、明细按店拆分")
    void cancelByBuyer_multiShop_rollbackPerSubOrder() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA(), subB()));
        when(paymentOrderRepository.findByOrderId(MASTER_ID)).thenReturn(paymentOf(MASTER_ID));

        useCase.cancelByBuyer(BUYER_ID, MASTER_ID, "多店取消");

        verify(inventoryFacade).rollback(SUB_A, List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3)));
        verify(inventoryFacade).rollback(SUB_B, List.of(new StockChangeItem(SKU_B1, 1)));
        verify(paymentPort).closePay("PAY20260907000001");
    }

    /* ================= fail path ================= */

    /**
     * fail-1 主单不存在：404 按不存在呈现（order.master_not_found），
     * 无任何编排动作（防存在性泄露）。
     */
    @Test
    @DisplayName("主单不存在：404 按不存在呈现")
    void cancelByBuyer_masterNotFound_rejected() {
        when(masterOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> useCase.cancelByBuyer(BUYER_ID, 999L, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).cancel(anyLong(), any());
        verify(inventoryFacade, never()).rollback(anyLong(), anyList());
        verify(paymentPort, never()).closePay(any());
    }

    /**
     * fail-2 归属不符：他人主单按不存在呈现（防越权与存在性泄露，
     * PaymentUseCase 发起支付同先例）。
     */
    @Test
    @DisplayName("归属不符：他人主单按不存在呈现")
    void cancelByBuyer_buyerMismatch_rejected() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());

        assertThatThrownBy(() -> useCase.cancelByBuyer(999L, MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).cancel(anyLong(), any());
        verify(paymentPort, never()).closePay(any());
    }

    /**
     * fail-3 超时入口主单不存在：404（系统触发数据异常防御，不静默）。
     */
    @Test
    @DisplayName("超时入口主单不存在：404")
    void cancelByTimeout_masterNotFound_rejected() {
        when(masterOrderRepository.getByID(999L)).thenReturn(null);

        assertThatThrownBy(() -> useCase.cancelByTimeout(999L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), 404);
                });
        verify(orderFacade, never()).cancel(anyLong(), any());
    }

    /**
     * fail-4 聚合守卫拒绝（已支付走退款 / 已发货不可取消）：
     * 订单侧非法迁移异常原样透传——库存回滚与支付关单不执行（同事务
     * 整体回滚，杜绝半程副作用）。
     */
    @Test
    @DisplayName("已支付/已发货子单取消：聚合守卫异常透传，无回滚无关单")
    void cancelByBuyer_illegalState_guardRejectsAndAborts() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        org.mockito.Mockito.doThrow(
                new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                        "仅待支付子单可取消（已支付/已发货非法迁移）", 400))
                .when(orderFacade).cancel(MASTER_ID, "取消");

        assertThatThrownBy(() -> useCase.cancelByBuyer(BUYER_ID, MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThatError(error, EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(), 400);
                });
        verify(inventoryFacade, never()).rollback(anyLong(), anyList());
        verify(paymentPort, never()).closePay(any());
    }

    /**
     * fail-5 库存回滚失败：异常原样透传（同事务整体回滚，订单状态不
     * 部分生效——no stock leaks 链路任一失败全回滚）。
     */
    @Test
    @DisplayName("库存回滚失败：异常透传，支付关单不执行")
    void cancelByBuyer_rollbackFailure_aborts() {
        when(masterOrderRepository.getByID(MASTER_ID)).thenReturn(pendingMaster());
        when(subOrderRepository.getByMasterOrderId(MASTER_ID)).thenReturn(List.of(subA()));
        org.mockito.Mockito.doThrow(
                new BusinessException("inventory.rollback_failed", "回滚失败", 409))
                .when(inventoryFacade).rollback(SUB_A, List.of(
                        new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3)));

        assertThatThrownBy(() -> useCase.cancelByBuyer(BUYER_ID, MASTER_ID, "取消"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThatError(error, "inventory.rollback_failed", 409));
        verify(paymentPort, never()).closePay(any());
    }

    /**
     * 业务异常断言助手（码 + HTTP 状态）。
     */
    private static void assertThatError(BusinessException error, String code, int httpStatus) {
        org.assertj.core.api.Assertions.assertThat(error.getBusinessCode()).isEqualTo(code);
        org.assertj.core.api.Assertions.assertThat(error.getHttpStatus()).isEqualTo(httpStatus);
    }
}