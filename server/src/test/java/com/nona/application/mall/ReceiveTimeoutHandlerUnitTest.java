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
 * 收货超时处理器（ORDER_RECEIVE：已发货 7 天未确认自动完成）
 * 场景测试——契约测试。
 * <p>
 * 覆盖：happy——fire 按类型路由到
 * {@link ConfirmReceiptUseCase#autoCompleteByTimeout} 并以 target（子订单
 * ID）透传；critical——幂等契约（重复 fire = 引擎恢复重扫重放：每次 fire
 * 都路由用例一次，业务幂等由用例短路承担，已完成子单短路成功且不重复
 * 发布完成事件）与 target 参数透传正确；fail——用例抛异常原样传播。
 * <p>
 * 装配纪律：处理器为普通类（不注册 Spring，遵循既有降级先例），
 * 构造器直接装配（@BeforeEach 重建，禁字段初始化）；对应用例以 mock 承载；
 * 桩纪律——以 lenient 豁免 UOE 挡道面，实现后已按断言面逐桩
 * 收回精确桩（零豁免，doThrow 桩经 assertThatThrownBy 消费）。
 *
 * @author nona9961
 */
@ExtendWith(MockitoExtension.class)
class ReceiveTimeoutHandlerUnitTest {

    /**
     * 到期子订单 ID（fire target 透传断言）
     */
    private static final long SUB_ORDER_ID = 720L;

    @Mock
    private ConfirmReceiptUseCase confirmReceiptUseCase;

    /**
     * 被测处理器（setUp 重建）
     */
    private ReceiveTimeoutHandler handler;

    /**
     * 每用例前重建被测处理器（mock 桩在各用例内布置）。
     */
    @BeforeEach
    void setUp() {
        handler = new ReceiveTimeoutHandler(confirmReceiptUseCase);
    }

    /* ================= happy path ================= */

    @Test
    @DisplayName("happy-1 type 注册：处理器声明 ORDER_RECEIVE 类型（注册表路由键）")
    void type_isOrderReceive() {
        assertThat(handler.type()).isEqualTo(TimeoutType.ORDER_RECEIVE);
    }

    @Test
    @DisplayName("happy-2 fire 路由：到期子单 target 原样透传自动完成编排（含完成事件发布，由用例承担）")
    void fire_forwardsToAutoCompleteByTimeout() {
        handler.fire(SUB_ORDER_ID);

        verify(confirmReceiptUseCase).autoCompleteByTimeout(SUB_ORDER_ID);
    }

    /* ================= critical path ================= */

    @Test
    @DisplayName("critical-1 幂等契约（恢复重扫重放）：重复 fire 每次路由用例一次，业务幂等由用例短路保证")
    void fire_repeatedDelegation_idempotencyByUseCase() {
        handler.fire(SUB_ORDER_ID);
        handler.fire(SUB_ORDER_ID);

        verify(confirmReceiptUseCase, times(2)).autoCompleteByTimeout(SUB_ORDER_ID);
    }

    @Test
    @DisplayName("critical-2 target 透传正确性：不同到期子单分别路由，互不串扰（subOrderId 级独立性）")
    void fire_targetParamIsolation() {
        handler.fire(SUB_ORDER_ID);
        handler.fire(SUB_ORDER_ID + 1);

        verify(confirmReceiptUseCase).autoCompleteByTimeout(SUB_ORDER_ID);
        verify(confirmReceiptUseCase).autoCompleteByTimeout(SUB_ORDER_ID + 1);
    }

    /* ================= fail path ================= */

    @Test
    @DisplayName("fail-1 用例异常传播：自动完成编排抛业务异常 → fire 原样透传（引擎侧捕获记录，回滚重扫）")
    void fire_propagatesUseCaseException() {
        final BusinessException boom = new BusinessException(
                EcommerceBusinessCode.ORDER_SUB_NOT_FOUND.code(), "子订单不存在", 404);
        doThrow(boom).when(confirmReceiptUseCase).autoCompleteByTimeout(SUB_ORDER_ID);

        assertThatThrownBy(() -> handler.fire(SUB_ORDER_ID))
                .isSameAs(boom);
    }
}