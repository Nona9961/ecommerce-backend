package com.nona.web;

import com.nona.acceptance.AcceptanceDbSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mock 网关回调送达端点装配面验收测试（支付/退款 HTTP 送达面接线，绿
 * 阶段装配面清单实现）。
 * <p>
 * 装配面清单（5 项）——mock 覆盖不到的真实面经真实
 * Spring 装配 + MockMvc 全链验证：
 * <ol>
 *     <li><b>安全链放行</b>（核心装配面）：无 token 直达内部端点
 *         （SecurityConfig {@code /internal/**} permitAll）——孤儿回调
 *         直达编排返回 404 业务码证明「放行 + 端点 + 编排」全链可达；</li>
 *     <li><b>PAY 回调真链</b>：端点 → 渠道校验 → 支付回调编排事务——支付
 *         单 PAID + 留痕四字段 + 相对窗口时刻 + 子单/主单派生 + 库存
 *         confirmDeduct + CONFIRM 流水；</li>
 *     <li><b>重复回调幂等</b>（HTTP 面）：第二次拒达 400
 *         {@code payment.status_illegal} + 留痕仍追加 + 订单/库存零二次
 *         变更；</li>
 *     <li><b>结构/反序列化面</b>：非法枚举（HttpMessageNotReadable）与
 *         缺 {@code amountCents}（JSR {@code @NotNull}）→ 400
 *         {@code generic.validation_failed}，均拒绝且不消费；</li>
 *     <li><b>REFUND 回调真链</b>：端点分发 REFUND 到退款编排——退款单
 *         SUCCEEDED + 留痕 + 子单/主单 REFUNDED + 未发货回补
 *         （REFUND_RESTORE）。</li>
 * </ol>
 * 数据准备：测试内 JDBC 直插（测试库 ecommerce_test，固定 ID 段
 * 975xx=PAY 链 / 976xx=REFUND 链，grep 全测试树零占用）；执行 = 真实
 * Spring 装配 + MockMvc（无 token，验证安全链放行）；断言 = JDBC 直查
 * 主库落库值（ecommerce_test 不进 CDC 白名单——无镜像侧，无 poll
 * 等待面，等待面 = 断言面同构不适用）。
 * <p>
 * 隔离纪律（testing-guidelines Shared-DB 11 条）：段位分区 + 禁 seed
 * 段；{@code @BeforeEach} 主单维度幂等清理 + {@code @AfterEach} 类末兜底
 * 清理（双保险）双链同做；无全表 DELETE（只按自身主单/段位谓词）；全
 * 断言自带自身段谓词过滤，无全局扫描；每用例 @BeforeEach 重建 fixture，
 * 任意方法序/类序自包含。
 * <p>
 * 单测（MockGatewayCallbackControllerUnitTest 9 例）已锁接线分发语义；
 * 本类只验证 mock 覆盖不到的装配面，不重复业务断言。
 *
 * @author nona9961
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class MockGatewayCallbackAcTest {

    /** 回调送达端点路径（安全链放行面核心）。 */
    private static final String ENDPOINT = "/internal/mock-gateway/callback";

    /** PAY 链店铺（子单/库存 tenant 锚点，975xx 段）。 */
    private static final long SHOP_A = 97501L;

    /** PAY 链买家。 */
    private static final long BUYER_A = 97502L;

    /** PAY 链主单（PENDING_PAYMENT）。 */
    private static final long MASTER = 97503L;

    /** PAY 链子单（PENDING_PAYMENT，tenant=SHOP_A）。 */
    private static final long SUB = 97504L;

    /** PAY 链支付单（PENDING_PAYMENT，pay_no 唯一）。 */
    private static final long PAYMENT = 97505L;

    /** PAY 链 SKU（库存行/订单项锚点）。 */
    private static final long SKU = 97506L;

    /** PAY 链订单项。 */
    private static final long ITEM = 97507L;

    /** PAY 链库存行（held=1 预占态）。 */
    private static final long INV_ITEM = 97508L;

    /** REFUND 链店铺（976xx 段）。 */
    private static final long SHOP_R = 97601L;

    /** REFUND 链买家。 */
    private static final long BUYER_R = 97602L;

    /** REFUND 链主单（REFUNDING）。 */
    private static final long MASTER_R = 97603L;

    /** REFUND 链子单（REFUNDING，tenant=SHOP_R）。 */
    private static final long SUB_R = 97604L;

    /** REFUND 链支付单（PAID，pay_no 唯一）。 */
    private static final long PAYMENT_R = 97605L;

    /** REFUND 链 SKU。 */
    private static final long SKU_R = 97606L;

    /** REFUND 链订单项。 */
    private static final long ITEM_R = 97607L;

    /** REFUND 链库存行（sold=1 已扣减态）。 */
    private static final long INV_ITEM_R = 97608L;

    /** REFUND 链退款单（PENDING + 受理流水占位）。 */
    private static final long REFUND_R = 97609L;

    /** PAY 链支付单号（回调 body 回显同值）。 */
    private static final String PAY_NO = "PAY_CB9751";

    /** PAY 链回调渠道流水（受理占位回传）。 */
    private static final String TXN_PAY = "TXN-CB975-1";

    /** REFUND 链支付单号。 */
    private static final String PAY_NO_R = "PAY_CB9761";

    /** REFUND 链退款单号。 */
    private static final String REFUND_NO_R = "REF_CB9761";

    /** REFUND 链回调渠道流水（必须 = 退款单受理占位流水）。 */
    private static final String TXN_REF = "TXN-RF-976-1";

    /** 回调金额（分；与受理金额一致）。 */
    private static final long AMOUNT = 5300L;

    /** MockMvc 客户端（真实安全过滤链 + 控制器装配）。 */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 每用例前：主单维度幂等清理（PAY/REFUND 双链）+ 直插基础行——PAY
     * 链（主单 + 子单 + 订单项 + 库存 held=1 + 支付单 PENDING）与 REFUND
     * 链（主单 REFUNDING + 子单 REFUNDING + 订单项 + 库存 sold=1 + 支付
     * 单 PAID + 退款单 PENDING 占位受理流水）。
     */
    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            deletePayChain(conn);
            deleteRefundChain(conn);
            insertPayRows(conn);
            insertRefundRows(conn);
        }
    }

    /**
     * 类末兜底清理（双保险）：防最后用例残留——任意执行序下本类自包含
     * （与 {@code @BeforeEach} 同维度清理，两链同做）。
     */
    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            deletePayChain(conn);
            deleteRefundChain(conn);
        }
    }

    /**
     * 面 1（核心装配面）：安全链放行——无 token 直达
     * {@code /internal/mock-gateway/callback}；孤儿回调（支付单不存在）
     * 经渠道校验后直达支付编排返回 404 {@code payment.not_found}——非
     * 401 认证拦截证明安全链放行到位，业务码证明端点 → 渠道校验 → 编
     * 排全链可达且异常由 ExceptionAdviser 统一映射。
     */
    @Test
    @DisplayName("面1 安全链放行：无token直达端点且孤儿回调404业务码透传")
    void securityChain_internalPermitAll_withoutToken() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody("PAY", "PAY_ORPHAN9752", null, "SUCCESS",
                                "TXN-ORPHAN-1", AMOUNT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment.not_found"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 面 2：PAY 回调真链（端点 → 渠道校验 → 支付回调编排事务）——合法
     * PAY body 无 token POST 后直查主库：① 支付单 PENDING→PAID +
     * channel_txn_no 回显落位；② 留痕 1 行（callback_type/result/
     * channel_txn_no/amount_cents 四字段 + occurred_at 相对窗口
     * BETWEEN now±1min，禁绝对日期）；③ 子单 PAID + 主单派生 PAID；④
     * 库存 confirmDeduct：held 1→0 / sold 0→1 + inventory_log CONFIRM
     * 行（(order_id=SUB, sku_id) 键）。
     */
    @Test
    @DisplayName("面2 PAY回调真链：支付单/留痕/订单派生/库存扣减全链落库")
    void payCallback_realChain_viaHttpEndpoint() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody("PAY", PAY_NO, null, "SUCCESS", TXN_PAY, AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("success"))
                .andExpect(jsonPath("$.success").value(true));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM payment_order WHERE id = ?", PAYMENT)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT channel_txn_no FROM payment_order WHERE id = ?", PAYMENT))
                    .isEqualTo(TXN_PAY);
            // 留痕 1 行：四字段 + occurred_at 相对窗口（UTC 字面 now±1min）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM payment_callback_log WHERE payment_order_id = ?"
                            + " AND callback_type = 'PAY' AND result = 'SUCCESS'"
                            + " AND channel_txn_no = ? AND amount_cents = ?"
                            + " AND occurred_at BETWEEN ? AND ?",
                    PAYMENT, TXN_PAY, AMOUNT,
                    AcceptanceDbSupport.utcOffset(-1), AcceptanceDbSupport.utcOffset(1)))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("PAID");
            // 库存 confirmDeduct：held 1→0 / sold 0→1 + CONFIRM 流水 (order_id=SUB, sku_id)
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT held FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU)).isEqualTo("1");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT type FROM inventory_log WHERE sku_id = ? AND order_id = ?"
                            + " ORDER BY id DESC LIMIT 1", SKU, SUB)).isEqualTo("CONFIRM");
        }
    }

    /**
     * 面 3：重复回调幂等（HTTP 面）——同号同 txn 第二次 POST：响应 400
     * + 业务码 {@code payment.status_illegal}（jsonPath $.code）；留痕
     * 仍追加第 2 行（幂等命中仍留痕，对账不依赖迁移成败）；库存流水/
     * 子单/主单零二次变更。
     */
    @Test
    @DisplayName("面3 重复回调幂等：HTTP面第二次拒达+留痕追加+零二次变更")
    void duplicateCallback_httpRejected_traceAppended() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody("PAY", PAY_NO, null, "SUCCESS", TXN_PAY, AMOUNT)))
                .andExpect(status().isOk());
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM payment_callback_log WHERE payment_order_id = ?", PAYMENT))
                    .isEqualTo(1L);
            final long logCount = AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU);
            final long subStatus = AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM sub_order WHERE id = ? AND status = 'PAID'", SUB);
            assertThat(subStatus).isEqualTo(1L);
            // 重放同号回调：状态守卫拒绝（payment.status_illegal）HTTP 400 映射
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payBody("PAY", PAY_NO, null, "SUCCESS", TXN_PAY, AMOUNT)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("payment.status_illegal"))
                    .andExpect(jsonPath("$.success").value(false));
            // 留痕第二条落库；库存流水/子单/主单零二次变更
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM payment_callback_log WHERE payment_order_id = ?", PAYMENT))
                    .isEqualTo(2L);
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM inventory_log WHERE sku_id = ?", SKU)).isEqualTo(logCount);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB)).isEqualTo("PAID");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER)).isEqualTo("PAID");
        }
    }

    /**
     * 面 4a：结构/反序列化面——body {@code "type":"BOGUS"} 非法枚举 →
     * Jackson 反序列化拒绝（HttpMessageNotReadable 路径）→ 400 +
     * {@code generic.validation_failed}（实测钉死精确码）；请求未达编
     * 排（孤儿 payNo 素材，拒绝且不消费）。
     */
    @Test
    @DisplayName("面4a 非法枚举字符串：反序列化拒绝400 generic.validation_failed")
    void bogusType_deserializationRejected() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOGUS\",\"payNo\":\"PAY_BOGUS9753\","
                                + "\"result\":\"SUCCESS\",\"channelTxnNo\":\"TXN-BOGUS-1\","
                                + "\"amountCents\":" + AMOUNT + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 面 4b：结构/反序列化面——body 缺 {@code amountCents} → JSR
     * {@code @NotNull} 校验面（MethodArgumentNotValidException）→ 400 +
     * {@code generic.validation_failed}；请求未达编排（拒绝且不消费）。
     */
    @Test
    @DisplayName("面4b 缺amountCents：JSR结构校验拒绝400 generic.validation_failed")
    void missingAmount_jsrRejected() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PAY\",\"payNo\":\"PAY_MISS9753\","
                                + "\"refundNo\":null,\"result\":\"SUCCESS\","
                                + "\"channelTxnNo\":\"TXN-MISS-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("generic.validation_failed"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 面 5：REFUND 回调真链（端点分发 REFUND → 退款编排）——合法 REFUND
     * body（refundNo 配套 + 渠道流水 = 受理占位）无 token POST 后直查主
     * 库：退款单 PENDING→SUCCEEDED + 留痕 1 行（相对窗口）+ 子单 REFUNDED
     * + 主单派生 REFUNDED + 未发货回补（I7：sold 1→0 / available 9→10 +
     * REFUND_RESTORE 流水 (order_id=SUB_R, sku_id) 键）。
     * <p>
     * 裁剪决定：保留——本面体量可控（fixture 仅多一张 refund_order 直
     * 插，其余行与面 2 共用主单维度清理形态），且是「端点 REFUND 分发」
     * 的 HTTP 面端到端证明（RefundFlowAcTest 只覆盖 use case 直调面，
     * 本面补齐送达面分发 + 反序列化 + 渠道校验配套语义）。
     */
    @Test
    @DisplayName("面5 REFUND回调真链：退款单/留痕/订单派生/库存回补全链落库")
    void refundCallback_realChain_viaHttpEndpoint() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody("REFUND", PAY_NO_R, REFUND_NO_R, "SUCCESS", TXN_REF, AMOUNT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("success"))
                .andExpect(jsonPath("$.success").value(true));
        try (Connection conn = AcceptanceDbSupport.mysql()) {
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM refund_order WHERE id = ?", REFUND_R)).isEqualTo("SUCCEEDED");
            // 留痕 1 行：四字段 + occurred_at 相对窗口（UTC 字面 now±1min）
            assertThat(AcceptanceDbSupport.count(conn,
                    "SELECT COUNT(*) FROM refund_callback_log WHERE refund_order_id = ?"
                            + " AND callback_type = 'REFUND' AND result = 'SUCCESS'"
                            + " AND channel_txn_no = ? AND amount_cents = ?"
                            + " AND occurred_at BETWEEN ? AND ?",
                    REFUND_R, TXN_REF, AMOUNT,
                    AcceptanceDbSupport.utcOffset(-1), AcceptanceDbSupport.utcOffset(1)))
                    .isEqualTo(1L);
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM sub_order WHERE id = ?", SUB_R)).isEqualTo("REFUNDED");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT status FROM master_order WHERE id = ?", MASTER_R)).isEqualTo("REFUNDED");
            // I7 未发货回补：sold 1→0 / available 9→10 + REFUND_RESTORE 流水 (order_id=SUB_R, sku_id)
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT sold FROM inventory_item WHERE sku_id = ?", SKU_R)).isEqualTo("0");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT available FROM inventory_item WHERE sku_id = ?", SKU_R)).isEqualTo("10");
            assertThat(AcceptanceDbSupport.stringValue(conn,
                    "SELECT type FROM inventory_log WHERE sku_id = ? AND order_id = ?"
                            + " ORDER BY id DESC LIMIT 1", SKU_R, SUB_R)).isEqualTo("REFUND_RESTORE");
        }
    }

    /**
     * PAY 链主单维度幂等清理（payment_callback_log → payment_order →
     * inventory_log → inventory_item → order_item → sub_order →
     * master_order，无全表 DELETE——只按自身主单/段位谓词）。
     *
     * @param conn 测试库连接
     * @throws Exception SQL 异常
     */
    private static void deletePayChain(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn, "DELETE FROM payment_callback_log WHERE payment_order_id IN"
                + " (SELECT id FROM payment_order WHERE order_id = ?)", MASTER);
        AcceptanceDbSupport.update(conn, "DELETE FROM payment_order WHERE order_id = ?", MASTER);
        AcceptanceDbSupport.update(conn, "DELETE FROM inventory_log WHERE sku_id = ?", SKU);
        AcceptanceDbSupport.update(conn, "DELETE FROM inventory_item WHERE sku_id = ?", SKU);
        AcceptanceDbSupport.update(conn, "DELETE FROM order_item WHERE sub_order_id IN"
                + " (SELECT id FROM sub_order WHERE master_order_id = ?)", MASTER);
        AcceptanceDbSupport.update(conn, "DELETE FROM sub_order WHERE master_order_id = ?", MASTER);
        AcceptanceDbSupport.update(conn, "DELETE FROM master_order WHERE id = ?", MASTER);
    }

    /**
     * REFUND 链主单维度幂等清理（refund_callback_log → refund_order →
     * payment_callback_log → payment_order → inventory_log →
     * inventory_item → order_item → sub_order → master_order）。
     *
     * @param conn 测试库连接
     * @throws Exception SQL 异常
     */
    private static void deleteRefundChain(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn, "DELETE FROM refund_callback_log WHERE refund_order_id IN"
                + " (SELECT id FROM refund_order WHERE sub_order_id IN (SELECT id FROM sub_order"
                + " WHERE master_order_id = ?))", MASTER_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM refund_order WHERE sub_order_id IN"
                + " (SELECT id FROM sub_order WHERE master_order_id = ?)", MASTER_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM payment_callback_log WHERE payment_order_id IN"
                + " (SELECT id FROM payment_order WHERE order_id = ?)", MASTER_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM payment_order WHERE order_id = ?", MASTER_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM inventory_log WHERE sku_id = ?", SKU_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM inventory_item WHERE sku_id = ?", SKU_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM order_item WHERE sub_order_id IN"
                + " (SELECT id FROM sub_order WHERE master_order_id = ?)", MASTER_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM sub_order WHERE master_order_id = ?", MASTER_R);
        AcceptanceDbSupport.update(conn, "DELETE FROM master_order WHERE id = ?", MASTER_R);
    }

    /**
     * 直插 PAY 链基础行（PaymentCallbackAcTest 同构：主单 PENDING_PAYMENT
     * + 子单 PENDING_PAYMENT + 订单项 + 库存 held=1 预占态 + 支付单
     * PENDING_PAYMENT，pay_no 唯一）。
     *
     * @param conn 测试库连接
     * @throws Exception SQL 异常
     */
    private static void insertPayRows(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER, "ORD_CB9751",
                BUYER_A, "张三", "13800000000", "浙江省", "杭州市", "西湖区", "文一西路 1 号",
                5000L, 300L, 0L, AMOUNT, "PENDING_PAYMENT");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB, String.valueOf(SHOP_A),
                MASTER, SHOP_A, "SUB_CB9751", "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                "文一西路 1 号", 5000L, 300L, 0L, AMOUNT, "PENDING_PAYMENT", null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM,
                String.valueOf(SHOP_A), SUB, SKU, SKU, "fixture毛衣", 5000L, 1, 5000L);
        // 预占态：available=9 / held=1 / sold=0
        AcceptanceDbSupport.update(conn,
                "INSERT INTO inventory_item (create_time, update_time, id, tenant_id, sku_id,"
                        + " available, held, sold, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), INV_ITEM,
                String.valueOf(SHOP_A), SKU, 9, 1, 0, 0);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO payment_order (create_time, update_time, id, pay_no, order_id, amount,"
                        + " channel, timeout_at, status, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), PAYMENT, PAY_NO,
                MASTER, AMOUNT, "MOCK", AcceptanceDbSupport.utcOffset(60),
                "PENDING_PAYMENT", false);
    }

    /**
     * 直插 REFUND 链基础行（RefundFlowAcTest 同构：主单 REFUNDING + 子单
     * REFUNDING + 订单项 + 库存 sold=1 已扣减态 + 支付单 PAID + 退款单
     * PENDING（channel_refund_txn_no 受理占位，回调必须回传同值）。
     *
     * @param conn 测试库连接
     * @throws Exception SQL 异常
     */
    private static void insertRefundRows(Connection conn) throws Exception {
        AcceptanceDbSupport.update(conn,
                "INSERT INTO master_order (create_time, update_time, id, order_no, buyer_id,"
                        + " recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), MASTER_R, "ORD_CB9761",
                BUYER_R, "李四", "13900000000", "浙江省", "杭州市", "西湖区", "文一西路 2 号",
                5000L, 300L, 0L, AMOUNT, "REFUNDING");
        AcceptanceDbSupport.update(conn,
                "INSERT INTO sub_order (create_time, update_time, id, tenant_id, master_order_id,"
                        + " shop_id, sub_order_no, recipient, phone, province, city, district, detail,"
                        + " goods_amount, freight_amount, discount, paid_amount, status, waybill_id, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), SUB_R, String.valueOf(SHOP_R),
                MASTER_R, SHOP_R, "SUB_CB9761", "李四", "13900000000", "浙江省", "杭州市", "西湖区",
                "文一西路 2 号", 5000L, 300L, 0L, AMOUNT, "REFUNDING", null, false);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO order_item (create_time, update_time, id, tenant_id, sub_order_id,"
                        + " product_id, sku_id, product_name, unit_price, quantity, subtotal)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), ITEM_R,
                String.valueOf(SHOP_R), SUB_R, SKU_R, SKU_R, "fixture牛仔裤", 5000L, 1, 5000L);
        // 已支付扣减后形态：available=9 / held=0 / sold=1
        AcceptanceDbSupport.update(conn,
                "INSERT INTO inventory_item (create_time, update_time, id, tenant_id, sku_id,"
                        + " available, held, sold, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), INV_ITEM_R,
                String.valueOf(SHOP_R), SKU_R, 9, 0, 1, 0);
        AcceptanceDbSupport.update(conn,
                "INSERT INTO payment_order (create_time, update_time, id, pay_no, order_id, amount,"
                        + " channel, timeout_at, status, claimed)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), PAYMENT_R, PAY_NO_R,
                MASTER_R, AMOUNT, "MOCK", AcceptanceDbSupport.utcOffset(-120),
                "PAID", false);
        // 退款单 PENDING + 受理流水占位（回调 body 必须回传同值）
        AcceptanceDbSupport.update(conn,
                "INSERT INTO refund_order (create_time, update_time, id, refund_no, pay_no,"
                        + " sub_order_id, amount, shipped_at_apply, reason, status,"
                        + " channel_refund_txn_no)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AcceptanceDbSupport.utcNow(), AcceptanceDbSupport.utcNow(), REFUND_R, REFUND_NO_R,
                PAY_NO_R, SUB_R, AMOUNT, false, "尺码不合适", "PENDING", TXN_REF);
    }

    /**
     * 构造回调请求体 JSON（字段名对齐 MockGatewayCallbackRequest record
     * 组件：type/payNo/refundNo/result/channelTxnNo/amountCents；枚举值
     * 即线上 JSON 值）。
     *
     * @param type         回调类型（PAY/REFUND/BOGUS 等素材）
     * @param payNo        支付单号
     * @param refundNo     退款单号（可为 null → JSON null）
     * @param result       渠道结果（SUCCESS/FAIL）
     * @param channelTxnNo 渠道流水号
     * @param amountCents  回调金额（分）
     * @return JSON 请求体
     */
    private static String payBody(String type, String payNo, String refundNo, String result,
                                  String channelTxnNo, Long amountCents) {
        return "{\"type\":\"" + type + "\",\"payNo\":\"" + payNo + "\",\"refundNo\":"
                + (refundNo == null ? "null" : "\"" + refundNo + "\"")
                + ",\"result\":\"" + result + "\",\"channelTxnNo\":\"" + channelTxnNo
                + "\",\"amountCents\":" + amountCents + "}";
    }
}