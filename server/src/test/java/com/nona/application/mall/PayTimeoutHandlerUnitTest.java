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
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 支付超时处理器（ORDER_PAY：30 分钟待支付自动关单回滚）场景测试
 * ——契约测试。
 * <p>
 * 覆盖：happy——fire 按类型路由到 {@link CancelOrderUseCase#cancelByTimeout}
 * 并以 target（主订单 ID）透传；critical——幂等契约（重复 fire = 引擎恢复
 * 重扫重放：每次 fire 都路由用例一次，业务幂等由用例短路承担）与 target
 * 参数透传正确；fail——用例抛异常原样传播。
 * <p>
 * 装配纪律：处理器为普通类（不注册 Spring，遵循既有降级先例），
 * 构造器直接装配（@BeforeEach 重建，禁字段初始化）；对应用例以 mock 承载；
 * 桩纪律——以 lenient 豁免 UOE 挡道面，实现后已按断言面逐桩
 * 收回精确桩（零豁免，doThrow 桩经 assertThatThrownBy 消费）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class PayTimeoutHandlerUnitTest {

    /**
     * 到期主订单 ID（fire target 透传断言）
     */
    private static final long MASTER_ORDER_ID = 900L;

    @Mock
    private CancelOrderUseCase cancelOrderUseCase;

    /**
     * 被测处理器（setUp 重建）
     */
    private PayTimeoutHandler handler;

    /**
     * 每用例前重建被测处理器（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        handler = new PayTimeoutHandler(cancelOrderUseCase);
    }

    /* ================= happy path ================= */

    @Test
    @DisplayName("happy-1 type 注册：处理器声明 ORDER_PAY 类型（注册表路由键）")
    void type_isOrderPay() {
        assertThat(handler.type()).isEqualTo(TimeoutType.ORDER_PAY);
    }

    @Test
    @DisplayName("happy-2 fire 路由：到期主单 target 原样透传取消编排（幂等短路由用例承担）")
    void fire_forwardsToCancelByTimeout() {
        handler.fire(MASTER_ORDER_ID);

        verify(cancelOrderUseCase).cancelByTimeout(MASTER_ORDER_ID);
    }

    /* ================= critical path ================= */

    @Test
    @DisplayName("critical-1 幂等契约（恢复重扫重放）：重复 fire 每次路由用例一次，业务幂等由用例短路保证")
    void fire_repeatedDelegation_idempotencyByUseCase() {
        handler.fire(MASTER_ORDER_ID);
        handler.fire(MASTER_ORDER_ID);

        verify(cancelOrderUseCase, times(2)).cancelByTimeout(MASTER_ORDER_ID);
    }

    @Test
    @DisplayName("critical-2 target 透传正确性：不同到期主单分别路由，互不串扰（orderId 级独立性）")
    void fire_targetParamIsolation() {
        handler.fire(MASTER_ORDER_ID);
        handler.fire(MASTER_ORDER_ID + 1);

        verify(cancelOrderUseCase).cancelByTimeout(MASTER_ORDER_ID);
        verify(cancelOrderUseCase).cancelByTimeout(MASTER_ORDER_ID + 1);
    }

    /* ================= fail path ================= */

    @Test
    @DisplayName("fail-1 用例异常传播：取消编排抛业务异常 → fire 原样透传（引擎侧捕获记录，回滚重扫）")
    void fire_propagatesUseCaseException() {
        final BusinessException boom = new BusinessException(
                EcommerceBusinessCode.ORDER_MASTER_NOT_FOUND.code(), "主订单不存在", 404);
        doThrow(boom).when(cancelOrderUseCase).cancelByTimeout(MASTER_ORDER_ID);

        assertThatThrownBy(() -> handler.fire(MASTER_ORDER_ID))
                .isSameAs(boom);
    }
}