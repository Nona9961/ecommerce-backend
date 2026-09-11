package com.nona.inf.persistence;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.logistics.WaybillPO;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import com.nona.inf.persistence.po.payment.RefundOrderPO;
import com.nona.inf.persistence.repository.jpa.MasterOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.OrderItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.PaymentCallbackLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.PaymentOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.RefundCallbackLogJpaRepository;
import com.nona.inf.persistence.repository.jpa.RefundOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillJpaRepository;
import com.nona.inf.persistence.repository.jpa.WaybillTrackJpaRepository;
import com.nona.inf.timeout.TimeoutType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 交易域 PO 基座冒烟测试（V2 落库后启用）：9 张新表的 JPA 层往返冒烟
 * ——每表 repository.save + findById/反查往返断言（列映射 / 唯一约束
 * 生效 / 从表关联 / 租户 fail-closed），mock 测不到的装配面逐一验证。
 * <p>
 * <b>启用契约（红阶段声明）</b>：本类在红阶段以真实断言体交付（非
 * fail() 桩）——V2__trading_tables.sql 落库前运行 = Spring 上下文
 * validate 失败（9 表缺失）整体红 = 契约闸门生效；绿阶段 V2 落库 +
 * 9 个新 PO 进入 validate 校验面后本类直接转绿，无需改方法体。
 * 运行命令模板见红阶段报告（localtunnel + ECOM_DB_PASSWORD +
 * -Pfull -Dtest=TradingPoFoundationAcTest）。
 * <p>
 * 装配纪律：注入面 = 9 个 JPA 接口（Spring Data 自动实现，无领域仓储
 * bean——WU-55 前 5 个 DifferRepository 实现不存在）；租户面以
 * {@link TrackingContext#withScope} 建立（Hibernate {@code @TenantId}
 * 过滤器对 JPA 直查同样生效——写自动注入 tenant_id、读 fail-closed）；
 * 唯一约束冲突断言依赖 SimpleJpaRepository 独立事务（save 出栈即
 * flush 提交，冲突在调用点抛 DataIntegrityViolationException）。
 *
 * @author nona9961
 */
@SpringBootTest
class TradingPoFoundationAcTest {

    /**
     * 测试主键种子（自增，避开 Snowflake 面）
     */
    private static final AtomicLong IDS = new AtomicLong(1_000_000L);

    /**
     * 本类独占 id 段边界（与 {@link #IDS} 同面：1,000,000-1,999,999）——
     * {@link #cleanupOwnedRows} 段位清理谓词；全测试树无其他类使用本段
     * （grep 实证），段位 DELETE 零越界风险。
     */
    private static final long ID_SEGMENT_LOW = 1_000_000L;
    private static final long ID_SEGMENT_HIGH = 1_999_999L;

    /**
     * 店铺 A 租户（子单隔离面）
     */
    private static final String SHOP_A = "1001";

    /**
     * 店铺 B 租户（跨店 fail-closed 断言面）
     */
    private static final String SHOP_B = "1002";

    @Autowired
    private MasterOrderJpaRepository masterOrderJpaRepository;
    @Autowired
    private SubOrderJpaRepository subOrderJpaRepository;
    @Autowired
    private OrderItemJpaRepository orderItemJpaRepository;
    @Autowired
    private PaymentOrderJpaRepository paymentOrderJpaRepository;
    @Autowired
    private PaymentCallbackLogJpaRepository paymentCallbackLogJpaRepository;
    @Autowired
    private RefundOrderJpaRepository refundOrderJpaRepository;
    @Autowired
    private RefundCallbackLogJpaRepository refundCallbackLogJpaRepository;
    @Autowired
    private WaybillJpaRepository waybillJpaRepository;
    @Autowired
    private WaybillTrackJpaRepository waybillTrackJpaRepository;

    private static long nextId() {
        return IDS.incrementAndGet();
    }

    @Test
    @DisplayName("冒烟-1 主单往返：master_order 全局表全列映射（地址六列/金额四列/状态）")
    void masterOrderRoundTrip_persistsAllSnapshotColumns() {
        TrackingContext.withScope(() -> {
            final MasterOrderPO po = new MasterOrderPO();
            final long id = nextId();
            po.setId(id);
            po.setOrderNo("ORD202609080000000001");
            po.setBuyerId(9001L);
            po.setRecipient("张三");
            po.setPhone("13800000000");
            po.setProvince("上海市");
            po.setCity("上海市");
            po.setDistrict("浦东新区");
            po.setDetail("XX 路 1 号");
            po.setGoodsAmount(5000L);
            po.setFreightAmount(600L);
            po.setDiscount(0L);
            po.setPaidAmount(5600L);
            po.setStatus(MasterOrderStatus.PENDING_PAYMENT);
            masterOrderJpaRepository.save(po);

            final MasterOrderPO loaded = masterOrderJpaRepository
                    .findById(id)
                    .orElseThrow(() -> new AssertionError("主单往返装载失败"));
            Assertions.assertEquals(po.getOrderNo(), loaded.getOrderNo());
            Assertions.assertEquals(po.getBuyerId(), loaded.getBuyerId());
            Assertions.assertEquals("张三", loaded.getRecipient());
            Assertions.assertEquals("浦东新区", loaded.getDistrict());
            Assertions.assertEquals(5000L, loaded.getGoodsAmount());
            Assertions.assertEquals(600L, loaded.getFreightAmount());
            Assertions.assertEquals(5600L, loaded.getPaidAmount());
            Assertions.assertEquals(MasterOrderStatus.PENDING_PAYMENT, loaded.getStatus());
            Assertions.assertNotNull(loaded.getCreateTime());
        });
    }

    @Test
    @DisplayName("冒烟-2 子单+订单项往返：sub_order 主从表 + JSON 扩展列 + 超时 SQL 三列")
    void subOrderWithOrderItem_roundTrip_jsonAndTimeoutColumns() {
        final long subId = nextId();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            final SubOrderPO sub = new SubOrderPO();
            sub.setId(subId);
            sub.setMasterOrderId(nextId());
            sub.setShopId(1001L);
            sub.setSubOrderNo("SUB202609080000000001");
            sub.setRecipient("李四");
            sub.setPhone("13900000000");
            sub.setProvince("浙江省");
            sub.setCity("杭州市");
            sub.setDistrict("西湖区");
            sub.setDetail("XX 街 2 号");
            sub.setGoodsAmount(8800L);
            sub.setFreightAmount(0L);
            sub.setDiscount(800L);
            sub.setPaidAmount(8000L);
            sub.setStatus(SubOrderStatus.PAID);
            sub.setWaybillId(7001L);
            sub.setTimeoutAt(LocalDateTime.of(2026, 9, 11, 12, 0, 0));
            sub.setTimeoutType(TimeoutType.ORDER_SHIP);
            subOrderJpaRepository.save(sub);

            final OrderItemPO item = new OrderItemPO();
            item.setId(nextId());
            item.setSubOrderId(subId);
            item.setProductId(42L);
            item.setSkuId(4242L);
            item.setProductName("经典款 T 恤");
            item.setUnitPrice(8800L);
            item.setQuantity(1);
            item.setSubtotal(8800L);
            item.setMainImageUrl("/files/p42.png");
            item.setSpecSummary("颜色:黑,尺码:M");
            item.setSpecAttributes("{\"颜色\":\"黑\",\"尺码\":\"M\"}");
            item.setCustomAttributes("{\"定制\":\"无\"}");
            orderItemJpaRepository.save(item);

            final SubOrderPO loadedSub = subOrderJpaRepository
                    .findById(subId)
                    .orElseThrow(() -> new AssertionError("子单往返装载失败"));
            Assertions.assertEquals(SubOrderStatus.PAID, loadedSub.getStatus());
            Assertions.assertEquals(7001L, loadedSub.getWaybillId());
            Assertions.assertEquals(8800L, loadedSub.getGoodsAmount());
            Assertions.assertEquals(SHOP_A, loadedSub.getTenantID(), "tenant_id 自动注入=shopId");
            Assertions.assertEquals(TimeoutType.ORDER_SHIP, loadedSub.getTimeoutType(),
                    "超时类型列往返");
            Assertions.assertEquals(LocalDateTime.of(2026, 9, 11, 12, 0, 0),
                    loadedSub.getTimeoutAt(), "超时截止列往返");
            Assertions.assertEquals(Boolean.FALSE, loadedSub.getClaimed(), "认领位默认 false");

            final List<OrderItemPO> items = orderItemJpaRepository.findBySubOrderIdOrderByIdAsc(subId);
            Assertions.assertEquals(1, items.size(), "从表反查命中");
            final OrderItemPO loadedItem = items.get(0);
            Assertions.assertEquals("经典款 T 恤", loadedItem.getProductName());
            Assertions.assertEquals(8800L, loadedItem.getUnitPrice());
            Assertions.assertEquals("{\"颜色\":\"黑\",\"尺码\":\"M\"}", loadedItem.getSpecAttributes(),
                    "JSON 扩展列原样往返");
            Assertions.assertEquals("{\"定制\":\"无\"}", loadedItem.getCustomAttributes());
            Assertions.assertEquals("/files/p42.png", loadedItem.getMainImageUrl(), "主图 URL 列往返");
            Assertions.assertEquals("颜色:黑,尺码:M", loadedItem.getSpecSummary(), "规格摘要列往返");
        });
    }

    @Test
    @DisplayName("冒烟-3 租户 fail-closed：跨店装载子单=不存在（Hibernate @TenantId 过滤）")
    void subOrder_tenantFailClosed_crossShopLoadAbsent() {
        final long subId = nextId();
        final long masterOrderId = nextId();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            final SubOrderPO sub = new SubOrderPO();
            sub.setId(subId);
            sub.setMasterOrderId(masterOrderId);
            sub.setShopId(1001L);
            sub.setSubOrderNo("SUB202609080000000002");
            sub.setRecipient("王五");
            sub.setPhone("13700000000");
            sub.setProvince("北京市");
            sub.setCity("北京市");
            sub.setDistrict("朝阳区");
            sub.setDetail("XX 大道 3 号");
            sub.setGoodsAmount(1000L);
            sub.setFreightAmount(0L);
            sub.setDiscount(0L);
            sub.setPaidAmount(1000L);
            sub.setStatus(SubOrderStatus.PENDING_PAYMENT);
            subOrderJpaRepository.save(sub);
        });
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_B);
            Assertions.assertTrue(subOrderJpaRepository.findById(subId).isEmpty(),
                    "跨店按 ID 装载必须按不存在呈现（fail-closed）");
            Assertions.assertTrue(subOrderJpaRepository
                    .findByMasterOrderIdOrderByIdAsc(masterOrderId).isEmpty(), "跨店反查必须为空");
        });
    }

    @Test
    @DisplayName("冒烟-4 支付单往返 + pay_no/order_id 唯一约束生效")
    void paymentOrder_roundTrip_uniquePayNoAndOrderId() {
        TrackingContext.withScope(() -> {
            final long id = nextId();
            final PaymentOrderPO po = new PaymentOrderPO();
            po.setId(id);
            po.setPayNo("PAY202609080000000001");
            po.setOrderId(9001L);
            po.setAmount(5600L);
            po.setChannel("MOCK");
            po.setTimeoutAt(LocalDateTime.of(2026, 9, 8, 13, 0, 0));
            po.setStatus(PaymentOrderStatus.PENDING_PAYMENT);
            paymentOrderJpaRepository.save(po);

            final PaymentOrderPO loaded = paymentOrderJpaRepository
                    .findById(id)
                    .orElseThrow(() -> new AssertionError("支付单往返装载失败"));
            Assertions.assertEquals(PaymentOrderStatus.PENDING_PAYMENT, loaded.getStatus());
            Assertions.assertEquals("MOCK", loaded.getChannel());
            Assertions.assertNull(loaded.getChannelTxnNo(), "未回调流水为 NULL");

            final PaymentOrderPO dupPayNo = new PaymentOrderPO();
            dupPayNo.setId(nextId());
            dupPayNo.setPayNo("PAY202609080000000001");
            dupPayNo.setOrderId(9002L);
            dupPayNo.setAmount(5600L);
            dupPayNo.setChannel("MOCK");
            dupPayNo.setStatus(PaymentOrderStatus.PENDING_PAYMENT);
            Assertions.assertThrows(DataIntegrityViolationException.class,
                    () -> paymentOrderJpaRepository.save(dupPayNo),
                    "同 pay_no 二次插入必须被 uk_payment_order_pay_no 拒绝");

            final PaymentOrderPO dupOrder = new PaymentOrderPO();
            dupOrder.setId(nextId());
            dupOrder.setPayNo("PAY202609080000000002");
            dupOrder.setOrderId(9001L);
            dupOrder.setAmount(5600L);
            dupOrder.setChannel("MOCK");
            dupOrder.setStatus(PaymentOrderStatus.PENDING_PAYMENT);
            Assertions.assertThrows(DataIntegrityViolationException.class,
                    () -> paymentOrderJpaRepository.save(dupOrder),
                    "同 order_id 二次插入必须被 uk_payment_order_order_id 拒绝");
        });
    }

    @Test
    @DisplayName("冒烟-5 channel_txn_no：NULL 多行允许 + 同流水唯一冲突（重复回调防线）")
    void paymentOrder_channelTxnNo_nullMultipleAndUniqueConflict() {
        TrackingContext.withScope(() -> {
            for (int i = 1; i <= 2; i++) {
                final PaymentOrderPO po = new PaymentOrderPO();
                po.setId(nextId());
                po.setPayNo("PAY20260908000000001" + i);
                po.setOrderId(9010L + i);
                po.setAmount(100L);
                po.setChannel("MOCK");
                po.setStatus(PaymentOrderStatus.PENDING_PAYMENT);
                paymentOrderJpaRepository.save(po);
            }
            final PaymentOrderPO dupTxn = new PaymentOrderPO();
            dupTxn.setId(nextId());
            dupTxn.setPayNo("PAY202609080000000099");
            dupTxn.setOrderId(9099L);
            dupTxn.setAmount(100L);
            dupTxn.setChannel("MOCK");
            dupTxn.setStatus(PaymentOrderStatus.PAID);
            dupTxn.setChannelTxnNo("TXN-DUP");
            paymentOrderJpaRepository.save(dupTxn);

            final PaymentOrderPO sameTxn = new PaymentOrderPO();
            sameTxn.setId(nextId());
            sameTxn.setPayNo("PAY202609080000000098");
            sameTxn.setOrderId(9098L);
            sameTxn.setAmount(100L);
            sameTxn.setChannel("MOCK");
            sameTxn.setStatus(PaymentOrderStatus.PENDING_PAYMENT);
            sameTxn.setChannelTxnNo("TXN-DUP");
            Assertions.assertThrows(DataIntegrityViolationException.class,
                    () -> paymentOrderJpaRepository.save(sameTxn),
                    "同 channel_txn_no 二次插入必须被 uk_payment_order_channel_txn_no 拒绝");
        });
    }

    @Test
    @DisplayName("冒烟-6 支付回调留痕追加：从表反查按追加序 + refundNo NULL 往返")
    void paymentCallbackLog_appendRoundTrip_orderPreserved() {
        TrackingContext.withScope(() -> {
            final long paymentOrderId = nextId();
            final long first = nextId();
            final PaymentCallbackLogPO firstLog = new PaymentCallbackLogPO();
            firstLog.setId(first);
            firstLog.setPaymentOrderId(paymentOrderId);
            firstLog.setCallbackType(CallbackType.PAY);
            firstLog.setPayNo("PAY202609080000000003");
            firstLog.setRefundNo(null);
            firstLog.setResult(GatewayResult.SUCCESS);
            firstLog.setChannelTxnNo("TXN-0001");
            firstLog.setAmountCents(5600L);
            firstLog.setOccurredAt(LocalDateTime.of(2026, 9, 8, 12, 30, 0));
            paymentCallbackLogJpaRepository.save(firstLog);

            final PaymentCallbackLogPO secondLog = new PaymentCallbackLogPO();
            secondLog.setId(nextId());
            secondLog.setPaymentOrderId(paymentOrderId);
            secondLog.setCallbackType(CallbackType.PAY);
            secondLog.setPayNo("PAY202609080000000003");
            secondLog.setResult(GatewayResult.FAIL);
            secondLog.setChannelTxnNo("TXN-0001");
            secondLog.setAmountCents(5600L);
            secondLog.setOccurredAt(LocalDateTime.of(2026, 9, 8, 12, 31, 0));
            paymentCallbackLogJpaRepository.save(secondLog);

            final List<PaymentCallbackLogPO> logs = paymentCallbackLogJpaRepository
                    .findByPaymentOrderIdOrderByIdAsc(paymentOrderId);
            Assertions.assertEquals(2, logs.size(), "留痕两行按追加序反查");
            Assertions.assertEquals(GatewayResult.SUCCESS, logs.get(0).getResult());
            Assertions.assertEquals(GatewayResult.FAIL, logs.get(1).getResult());
            Assertions.assertNull(logs.get(0).getRefundNo(), "PAY 回调 refund_no 为 NULL");
            Assertions.assertEquals(LocalDateTime.of(2026, 9, 8, 12, 30, 0),
                    logs.get(0).getOccurredAt(), "收到时间列往返");
        });
    }

    @Test
    @DisplayName("冒烟-7 退款单往返：refund_no/sub_order_id 唯一 + 流水覆盖更新允许")
    void refundOrder_roundTrip_uniqueAndTxnOverwrite() {
        TrackingContext.withScope(() -> {
            final long id = nextId();
            final RefundOrderPO po = new RefundOrderPO();
            po.setId(id);
            po.setRefundNo("RF202609080000000001");
            po.setPayNo("PAY202609080000000003");
            po.setSubOrderId(5555L);
            po.setAmount(8000L);
            po.setShippedAtApply(false);
            po.setReason("七天无理由");
            po.setStatus(RefundOrderStatus.PENDING);
            po.setChannelRefundTxnNo("RF-TXN-001");
            refundOrderJpaRepository.save(po);

            final RefundOrderPO loaded = refundOrderJpaRepository
                    .findById(id)
                    .orElseThrow(() -> new AssertionError("退款单往返装载失败"));
            Assertions.assertEquals(RefundOrderStatus.PENDING, loaded.getStatus());
            Assertions.assertEquals("七天无理由", loaded.getReason());
            Assertions.assertEquals(Boolean.FALSE, loaded.getShippedAtApply());

            final RefundOrderPO dupRefundNo = new RefundOrderPO();
            dupRefundNo.setId(nextId());
            dupRefundNo.setRefundNo("RF202609080000000001");
            dupRefundNo.setPayNo("PAY202609080000000003");
            dupRefundNo.setSubOrderId(5556L);
            dupRefundNo.setAmount(1000L);
            dupRefundNo.setShippedAtApply(false);
            dupRefundNo.setStatus(RefundOrderStatus.PENDING);
            Assertions.assertThrows(DataIntegrityViolationException.class,
                    () -> refundOrderJpaRepository.save(dupRefundNo),
                    "同 refund_no 二次插入必须被 uk_refund_order_refund_no 拒绝");

            final RefundOrderPO dupSub = new RefundOrderPO();
            dupSub.setId(nextId());
            dupSub.setRefundNo("RF202609080000000002");
            dupSub.setPayNo("PAY202609080000000003");
            dupSub.setSubOrderId(5555L);
            dupSub.setAmount(1000L);
            dupSub.setShippedAtApply(false);
            dupSub.setStatus(RefundOrderStatus.PENDING);
            Assertions.assertThrows(DataIntegrityViolationException.class,
                    () -> refundOrderJpaRepository.save(dupSub),
                    "同 sub_order_id 二次插入必须被 uk_refund_order_sub_order_id 拒绝");

            final RefundOrderPO retry = new RefundOrderPO();
            retry.setId(nextId());
            retry.setRefundNo("RF202609080000000003");
            retry.setPayNo("PAY202609080000000003");
            retry.setSubOrderId(5557L);
            retry.setAmount(1000L);
            retry.setShippedAtApply(false);
            retry.setStatus(RefundOrderStatus.PENDING);
            retry.setChannelRefundTxnNo("RF-TXN-002");
            refundOrderJpaRepository.save(retry);
            final RefundOrderPO overwritten = new RefundOrderPO();
            overwritten.setId(retry.getId());
            overwritten.setRefundNo("RF202609080000000003");
            overwritten.setPayNo("PAY202609080000000003");
            overwritten.setSubOrderId(5557L);
            overwritten.setAmount(1000L);
            overwritten.setShippedAtApply(false);
            overwritten.setStatus(RefundOrderStatus.FAILED);
            overwritten.setChannelRefundTxnNo("RF-TXN-003");
            refundOrderJpaRepository.save(overwritten);
            Assertions.assertEquals("RF-TXN-003", refundOrderJpaRepository
                            .findById(retry.getId()).orElseThrow().getChannelRefundTxnNo(),
                    "FAILED 重试受理覆盖更新流水（无唯一约束拦截）");
        });
    }

    @Test
    @DisplayName("冒烟-8 退款回调留痕往返：refund_callback_log 从表追加")
    void refundCallbackLog_roundTrip() {
        TrackingContext.withScope(() -> {
            final long refundOrderId = nextId();
            final RefundCallbackLogPO log = new RefundCallbackLogPO();
            log.setId(nextId());
            log.setRefundOrderId(refundOrderId);
            log.setCallbackType(CallbackType.REFUND);
            log.setPayNo("PAY202609080000000003");
            log.setRefundNo("RF202609080000000001");
            log.setResult(GatewayResult.SUCCESS);
            log.setChannelTxnNo("RF-TXN-001");
            log.setAmountCents(8000L);
            log.setOccurredAt(LocalDateTime.of(2026, 9, 8, 12, 40, 0));
            refundCallbackLogJpaRepository.save(log);

            final List<RefundCallbackLogPO> logs = refundCallbackLogJpaRepository
                    .findByRefundOrderIdOrderByIdAsc(refundOrderId);
            Assertions.assertEquals(1, logs.size(), "退款留痕从表反查命中");
            Assertions.assertEquals(CallbackType.REFUND, logs.get(0).getCallbackType());
            Assertions.assertEquals(LocalDateTime.of(2026, 9, 8, 12, 40, 0),
                    logs.get(0).getOccurredAt());
        });
    }

    @Test
    @DisplayName("冒烟-9 运单往返 + 在途唯一约束：同子单第二张在途拒绝、历史行 NULL 并存")
    void waybill_roundTrip_inTransitUniqueEnforced() {
        TrackingContext.withScope(() -> {
            final long subOrderId = 6666L;
            final long inTransitId = nextId();
            final WaybillPO po = new WaybillPO();
            po.setId(inTransitId);
            po.setSubOrderId(subOrderId);
            po.setCompany("顺丰速运");
            po.setTrackingNo("SF1234567890");
            po.setStatus(WaybillStatus.SHIPPED);
            po.setInTransit(Boolean.TRUE);
            waybillJpaRepository.save(po);

            Assertions.assertEquals(inTransitId, waybillJpaRepository
                    .findBySubOrderIdAndInTransitTrue(subOrderId)
                    .orElseThrow(() -> new AssertionError("在途锚点查询未命中"))
                    .getId(), "在途锚点 findInTransitBySubOrderId 装载面");
            Assertions.assertTrue(waybillJpaRepository.findByInTransitTrue().stream()
                            .anyMatch(w -> w.getId().equals(inTransitId)),
                    "在途全量扫描面命中刚插入行");

            final WaybillPO dupInTransit = new WaybillPO();
            dupInTransit.setId(nextId());
            dupInTransit.setSubOrderId(subOrderId);
            dupInTransit.setCompany("顺丰速运");
            dupInTransit.setTrackingNo("SF9999999999");
            dupInTransit.setStatus(WaybillStatus.PENDING_SHIPMENT);
            dupInTransit.setInTransit(Boolean.TRUE);
            Assertions.assertThrows(DataIntegrityViolationException.class,
                    () -> waybillJpaRepository.save(dupInTransit),
                    "同子单第二张在途运单必须被 uk_waybill_sub_order_in_transit 拒绝");

            final WaybillPO delivered = new WaybillPO();
            delivered.setId(nextId());
            delivered.setSubOrderId(subOrderId);
            delivered.setCompany("顺丰速运");
            delivered.setTrackingNo("SF8888888888");
            delivered.setStatus(WaybillStatus.DELIVERED);
            delivered.setInTransit(null);
            waybillJpaRepository.save(delivered);
            Assertions.assertTrue(waybillJpaRepository.findByInTransitTrue().stream()
                            .noneMatch(w -> w.getId().equals(delivered.getId())),
                    "签收行（in_transit NULL）不在扫描面");
            Assertions.assertTrue(waybillJpaRepository.findByInTransitTrue().stream()
                            .anyMatch(w -> w.getId().equals(inTransitId)),
                    "在途行仍被扫描面命中（历史行并存不影响）");

            // —— 清理：测试库与 app 共享（ecommerce_test），自建行残留会
            // 毒化 app 物流在途扫描（in_transit=TRUE 无轨迹行触发聚合守卫
            // 「运单轨迹列表不能为空」→ 整轮扫描停摆），断言完成后按自建
            // 主键逐行删除；dup 行被唯一约束拒绝未落库，findById 判存幂等化。
            waybillJpaRepository.findById(inTransitId).ifPresent(waybillJpaRepository::delete);
            waybillJpaRepository.findById(dupInTransit.getId()).ifPresent(waybillJpaRepository::delete);
            waybillJpaRepository.findById(delivered.getId()).ifPresent(waybillJpaRepository::delete);
        });
    }

    @Test
    @DisplayName("冒烟-10 运单轨迹追加：waybill_track 从表 append-only 反查")
    void waybillTrack_appendRoundTrip() {
        TrackingContext.withScope(() -> {
            final long waybillId = nextId();
            final WaybillTrackPO first = new WaybillTrackPO();
            first.setId(nextId());
            first.setWaybillId(waybillId);
            first.setStatus(WaybillStatus.SHIPPED);
            first.setOccurredAt(LocalDateTime.of(2026, 9, 8, 14, 0, 0));
            first.setDescription("已揽收");
            waybillTrackJpaRepository.save(first);

            final WaybillTrackPO second = new WaybillTrackPO();
            second.setId(nextId());
            second.setWaybillId(waybillId);
            second.setStatus(WaybillStatus.IN_TRANSIT);
            second.setOccurredAt(LocalDateTime.of(2026, 9, 8, 18, 0, 0));
            second.setDescription(null);
            waybillTrackJpaRepository.save(second);

            final List<WaybillTrackPO> tracks = waybillTrackJpaRepository
                    .findByWaybillIdOrderByIdAsc(waybillId);
            Assertions.assertEquals(2, tracks.size(), "轨迹按追加序反查");
            Assertions.assertEquals(WaybillStatus.SHIPPED, tracks.get(0).getStatus());
            Assertions.assertEquals(WaybillStatus.IN_TRANSIT, tracks.get(1).getStatus());
            Assertions.assertNull(tracks.get(1).getDescription(), "可空描述往返");
            Assertions.assertEquals(LocalDateTime.of(2026, 9, 8, 14, 0, 0),
                    tracks.get(0).getOccurredAt(), "轨迹时间列往返");

            // —— 清理：共享测试库残留的孤儿 waybill_track 行（无对应 waybill
            // 主表行）会污染反查面，按自建轨迹主键逐条删除（deleteByWaybillId
            // 派生删无事务会抛「No EntityManager...remove 拒绝」）。
            waybillTrackJpaRepository.deleteById(first.getId());
            waybillTrackJpaRepository.deleteById(second.getId());
        });
    }

    /**
     * 类级段位清理（2026-09-11 补，测试库卫生 WU 第二部分——类级兜底）：
     * ecommerce_test 由 app（dev demo）与本 AcTest 共享，本类直插行无清理
     * 时每轮 -Pfull 留下大量孤儿行——订单类残留被 app TimeoutScheduler
     * 拾取处理（app.log 实证 WARN taskId=1000006）、孤儿运单被 app 推进产生
     * 无主事件、在途无轨迹运单毒化物流扫描（整轮停摆）。本方法按本类独占
     * id 段幂等 DELETE（从表先删、主表后删；段位谓词不存在即 0 行天然
     * 幂等），与冒烟-9/10 方法内清理双保险：方法内清理防测试运行期间毒化
     * app 扫描面（保留不动），本兜底覆盖其余冒烟（冒烟-1~8 等）防跨轮残留。
     * order_item 按 sub_order_id 段删——经 diff 链路落的条目行 id 由
     * IDUtils.generateID() 雪花生成、不在测试 id 段内（
     * SubOrderRepositoryImpl.insertItemRow），按根行关联列段删才完整覆盖。
     */
    @AfterEach
    void cleanupOwnedRows() throws Exception {
        try (Connection mysql = AcceptanceDbSupport.mysql()) {
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM waybill_track WHERE waybill_id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM waybill WHERE id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM refund_callback_log WHERE refund_order_id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM payment_callback_log WHERE payment_order_id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM order_item WHERE sub_order_id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM sub_order WHERE id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM refund_order WHERE id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM payment_order WHERE id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM master_order WHERE id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
        }
    }
}