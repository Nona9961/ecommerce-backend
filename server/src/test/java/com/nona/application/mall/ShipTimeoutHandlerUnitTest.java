package com.nona.application.mall;

import com.nona.inf.timeout.TimeoutType;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 发货超时处理器（ORDER_SHIP：已支付 3 天未发货自动关单退款，B9.4②）
 * 场景测试——红阶段契约。
 * <p>
 * 覆盖：happy——fire 按类型路由到 {@link RefundUseCase#refundByShipTimeout}
 * 并以 target（子订单 ID）透传；critical——幂等契约（重复 fire = 引擎恢复
 * 重扫重放：每次 fire 都路由用例一次，业务幂等由用例短路承担，短路成功
 * 返回 null 不重复建单）与 target 参数透传正确；fail——用例抛异常原样传播。
 * <p>
 * 装配纪律：处理器为普通类（红阶段不注册 Spring，WU-032 决策 9 降级先例），
 * 构造器直接装配（@BeforeEach 重建，禁字段初始化）；对应用例以 mock 承载；
 * 桩纪律——红阶段以 lenient 豁免 UOE 挡道面，绿实现后已按断言面逐桩
 * 收回精确桩（零豁免，doThrow 桩经 assertThatThrownBy 消费）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class ShipTimeoutHandlerUnitTest {

    /**
     * 到期子订单 ID（fire target 透传断言）
     */
    private static final long SUB_ORDER_ID = 810L;

    @Mock
    private RefundUseCase refundUseCase;

    /**
     * 被测处理器（setUp 重建）
     */
    private ShipTimeoutHandler handler;

    /**
     * 每用例前重建被测处理器（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        handler = new ShipTimeoutHandler(refundUseCase);
    }

    /* ================= happy path ================= */

    @Test
    @DisplayName("happy-1 type 注册：处理器声明 ORDER_SHIP 类型（注册表路由键）")
    void type_isOrderShip() {
        assertThat(handler.type()).isEqualTo(TimeoutType.ORDER_SHIP);
    }

    @Test
    @DisplayName("happy-2 fire 路由：到期子单 target 原样透传退款编排（含关单+I7 库存回补，由用例承担）")
    void fire_forwardsToRefundByShipTimeout() {
        handler.fire(SUB_ORDER_ID);

        verify(refundUseCase).refundByShipTimeout(SUB_ORDER_ID);
    }

    /* ================= critical path ================= */

    @Test
    @DisplayName("critical-1 幂等契约（恢复重扫重放）：重复 fire 每次路由用例一次，业务幂等由用例短路保证")
    void fire_repeatedDelegation_idempotencyByUseCase() {
        handler.fire(SUB_ORDER_ID);
        handler.fire(SUB_ORDER_ID);

        verify(refundUseCase, times(2)).refundByShipTimeout(SUB_ORDER_ID);
    }

    @Test
    @DisplayName("critical-2 target 透传正确性：不同到期子单分别路由，互不串扰（subOrderId 级独立性）")
    void fire_targetParamIsolation() {
        handler.fire(SUB_ORDER_ID);
        handler.fire(SUB_ORDER_ID + 1);

        verify(refundUseCase).refundByShipTimeout(SUB_ORDER_ID);
        verify(refundUseCase).refundByShipTimeout(SUB_ORDER_ID + 1);
    }

    /* ================= fail path ================= */

    @Test
    @DisplayName("fail-1 用例异常传播：退款编排抛业务异常 → fire 原样透传（引擎侧捕获记录，回滚重扫）")
    void fire_propagatesUseCaseException() {
        final BusinessException boom = new BusinessException(
                EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), "子订单不存在", 404);
        doThrow(boom).when(refundUseCase).refundByShipTimeout(SUB_ORDER_ID);

        assertThatThrownBy(() -> handler.fire(SUB_ORDER_ID))
                .isSameAs(boom);
    }
}