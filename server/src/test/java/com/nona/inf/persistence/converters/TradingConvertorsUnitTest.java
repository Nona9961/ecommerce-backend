package com.nona.inf.persistence.converters;

import com.nona.domain.logistics.entity.Waybill;
import com.nona.domain.logistics.entity.WaybillStatus;
import com.nona.domain.logistics.entity.WaybillTrack;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.payment.entity.PaymentCallbackRecord;
import com.nona.domain.payment.entity.PaymentOrder;
import com.nona.domain.payment.entity.PaymentOrderStatus;
import com.nona.domain.payment.entity.RefundCallbackRecord;
import com.nona.domain.payment.entity.RefundOrder;
import com.nona.domain.payment.entity.RefundOrderStatus;
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.inf.persistence.po.logistics.WaybillPO;
import com.nona.inf.persistence.po.logistics.WaybillTrackPO;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.po.payment.PaymentCallbackLogPO;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import com.nona.inf.persistence.po.payment.RefundCallbackLogPO;
import com.nona.inf.persistence.po.payment.RefundOrderPO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易域 9 转换器映射契约单元测试（无容器）：PO ↔ 领域往返断言
 * （快照扁平化/重建、JSON 扩展列、Instant↔LocalDateTime UTC 字面、
 * in_transit 派生位）。领域守卫（金额自洽等）属领域单测面，本类只
 * 断言转换器映射本身。
 *
 * @author nona9961
 */
class TradingConvertorsUnitTest {

    private static final MasterOrderConvertor MASTER = new MasterOrderConvertor();
    private static final SubOrderConvertor SUB = new SubOrderConvertor(new OrderItemConvertor());
    private static final OrderItemConvertor ITEM = new OrderItemConvertor();
    private static final PaymentOrderConvertor PAYMENT =
            new PaymentOrderConvertor(new PaymentCallbackRecordConvertor());
    private static final RefundOrderConvertor REFUND =
            new RefundOrderConvertor(new RefundCallbackRecordConvertor());
    private static final WaybillConvertor WAYBILL =
            new WaybillConvertor(new WaybillTrackConvertor());

    private static AddressSnapshot address() {
        return new AddressSnapshot("张三", "13800000000", "上海市", "上海市", "浦东新区", "XX 路 1 号");
    }

    private static AmountDetail amount() {
        return new AmountDetail(5000L, 600L, 0L, 5600L);
    }

    @Test
    @DisplayName("主单：地址/金额快照六四列扁平化 + VO 重建 + 子单 id 集合透传")
    void masterOrder_roundTrip_snapshotColumnsAndSubOrderIds() {
        final MasterOrder root = new MasterOrder(1L, "ORD1", 9001L, address(), amount(),
                List.of(11L, 12L), List.of(), MasterOrderStatus.PAID);
        final MasterOrderPO po = MASTER.convertToPO(root);
        Assertions.assertEquals("ORD1", po.getOrderNo());
        Assertions.assertEquals("张三", po.getRecipient());
        Assertions.assertEquals("浦东新区", po.getDistrict());
        Assertions.assertEquals(600L, po.getFreightAmount());
        Assertions.assertEquals(MasterOrderStatus.PAID, po.getStatus());

        final MasterOrder back = MASTER.convertToRoot(po, List.of(11L, 12L));
        Assertions.assertEquals(1L, back.getId());
        Assertions.assertEquals("上海市", back.getAddress().getCity());
        Assertions.assertEquals(5600L, back.getAmount().getPaidAmount());
        Assertions.assertEquals(List.of(11L, 12L), back.getSubOrderIds());
        Assertions.assertEquals(MasterOrderStatus.PAID, back.getStatus());
        Assertions.assertThrows(com.nona.exceptions.BusinessException.class,
                () -> MASTER.convertToRoot(po, List.of()),
                "空子单 id 集合被领域 ORDER_SUB_EMPTY 守卫拒绝");
    }

    @Test
    @DisplayName("子单+订单项：快照列往返 + JSON 扩展列序列化/反序列化保序")
    void subOrder_roundTrip_itemJsonPreserved() {
        final Map<String, String> spec = new LinkedHashMap<>();
        spec.put("颜色", "黑");
        spec.put("尺码", "M");
        final OrderItem item = new OrderItem(42L, 4242L, "经典款 T 恤", 8800L, 1, 8800L,
                "/files/p42.png", "颜色:黑,尺码:M", spec, Map.of("定制", "无"));
        // 子单金额自洽守卫：goodsAmount 必须 = 条目小计和（8800）
        final AmountDetail subAmount = new AmountDetail(8800L, 0L, 800L, 8000L);
        final SubOrder root = new SubOrder(2L, 1L, 1001L, "SUB1", address(), subAmount,
                List.of(item), SubOrderStatus.PAID, 7001L);

        final SubOrderPO po = SUB.convertToPO(root);
        Assertions.assertNull(po.getTimeoutAt(), "超时三列 convertor 不触碰（D11）");
        Assertions.assertNull(po.getTimeoutType());
        Assertions.assertEquals(Boolean.FALSE, po.getClaimed(), "claimed 保持字段初始化值");

        final OrderItemPO itemPo = ITEM.toPO(item);
        Assertions.assertEquals("{\"颜色\":\"黑\",\"尺码\":\"M\"}", itemPo.getSpecAttributes(),
                "JSON 扩展列保序序列化");
        final SubOrder back = SUB.convertToRoot(po, List.of(itemPo));
        final OrderItem backItem = back.getItems().get(0);
        Assertions.assertEquals(Map.of("颜色", "黑", "尺码", "M"), backItem.getSpecAttributes());
        Assertions.assertEquals(Map.of("定制", "无"), backItem.getCustomAttributes());
        Assertions.assertEquals("经典款 T 恤", backItem.getProductName());
        Assertions.assertEquals(SubOrderStatus.PAID, back.getStatus());
        Assertions.assertEquals(7001L, back.getWaybillId());
    }

    @Test
    @DisplayName("订单项 JSON：空 Map 序列化为 {}（领域 null 归一为空集合恒非 null），空白列读回按空集合恢复")
    void orderItem_jsonEmptyAndBlank() {
        final OrderItem noSpec = new OrderItem(1L, 2L, "无扩展", 100L, 1, 100L,
                null, null, Map.of(), null);
        final OrderItemPO po = ITEM.toPO(noSpec);
        // 领域构造 freezeAttributes(null) 归一为空 Map → getter 恒非 null → 恒序列化 {}
        Assertions.assertEquals("{}", po.getSpecAttributes(), "空 Map → {}");
        Assertions.assertEquals("{}", po.getCustomAttributes(),
                "领域 null 归一为空 Map → 恒序列化 {}（null 列只出现在读路径）");

        // 读路径兜底：PO 列 null（历史数据/未填扩展）→ 领域恢复为不可变空集合
        final OrderItemPO nullColPo = new OrderItemPO();
        nullColPo.setId(99L);
        nullColPo.setProductId(1L);
        nullColPo.setSkuId(2L);
        nullColPo.setProductName("无扩展");
        nullColPo.setUnitPrice(100L);
        nullColPo.setQuantity(1);
        nullColPo.setSubtotal(100L);
        nullColPo.setMainImageUrl(null);
        nullColPo.setSpecSummary(null);
        nullColPo.setSpecAttributes(null);
        nullColPo.setCustomAttributes(null);
        final OrderItem back = ITEM.toDomain(nullColPo);
        Assertions.assertEquals(Map.of(), back.getSpecAttributes(), "空白列读回 → 不可变空集合");
        Assertions.assertEquals(Map.of(), back.getCustomAttributes());
    }

    @Test
    @DisplayName("支付单：Instant ↔ LocalDateTime UTC 字面往返 + 流水可空透传")
    void paymentOrder_utcRoundTrip() {
        final Instant timeout = LocalDateTime.of(2026, 9, 8, 13, 0, 0)
                .toInstant(ZoneOffset.UTC);
        final PaymentOrder root = new PaymentOrder(3L, "PAY1", 9001L, 5600L, "MOCK",
                timeout, PaymentOrderStatus.PAID, "TXN-1", List.of());
        final PaymentOrderPO po = PAYMENT.convertToPO(root);
        Assertions.assertEquals(LocalDateTime.of(2026, 9, 8, 13, 0, 0), po.getTimeoutAt(),
                "UTC 字面落列");
        Assertions.assertNull(po.getTimeoutType(), "超时 SQL 面 convertor 不读写");

        final PaymentOrder back = PAYMENT.convertToRoot(po, List.of());
        Assertions.assertEquals(timeout, back.getTimeoutAt(), "UTC 字面读回 = 原 Instant");
        Assertions.assertEquals("TXN-1", back.getChannelTxnNo());
        Assertions.assertEquals(PaymentOrderStatus.PAID, back.getStatus());
    }

    @Test
    @DisplayName("支付回调留痕：六字段 + occurredAt UTC 往返")
    void paymentCallbackRecord_utcRoundTrip() {
        final Instant occurred = LocalDateTime.of(2026, 9, 8, 12, 30, 0)
                .toInstant(ZoneOffset.UTC);
        final PaymentCallbackRecord record = new PaymentCallbackRecord(4L, 3L,
                CallbackType.PAY, "PAY1", null, GatewayResult.SUCCESS,
                "TXN-1", 5600L, occurred);
        final PaymentCallbackLogPO po = new PaymentCallbackRecordConvertor().toPO(record);
        Assertions.assertEquals(LocalDateTime.of(2026, 9, 8, 12, 30, 0), po.getOccurredAt());
        Assertions.assertNull(po.getRefundNo());

        final PaymentCallbackRecord back = new PaymentCallbackRecordConvertor().toDomain(po);
        Assertions.assertEquals(occurred, back.getOccurredAt());
        Assertions.assertEquals(GatewayResult.SUCCESS, back.getResult());
        Assertions.assertEquals(CallbackType.PAY, back.getCallbackType());
    }

    @Test
    @DisplayName("退款单：shippedAtApply/reason 直映射 + 留痕集合装载")
    void refundOrder_roundTrip() {
        final RefundOrder root = new RefundOrder(5L, "RF1", "PAY1", 5555L, 8000L,
                true, "七天无理由", RefundOrderStatus.PENDING, null, List.of());
        final RefundOrderPO po = REFUND.convertToPO(root);
        Assertions.assertEquals(Boolean.TRUE, po.getShippedAtApply());
        Assertions.assertEquals("七天无理由", po.getReason());
        Assertions.assertNull(po.getChannelRefundTxnNo());

        final RefundOrder back = REFUND.convertToRoot(po, List.of());
        Assertions.assertTrue(back.isShippedAtApply());
        Assertions.assertEquals(RefundOrderStatus.PENDING, back.getStatus());
        Assertions.assertEquals(5555L, back.getSubOrderId());
    }

    @Test
    @DisplayName("退款回调留痕：六字段 + occurredAt UTC 往返")
    void refundCallbackRecord_utcRoundTrip() {
        final Instant occurred = LocalDateTime.of(2026, 9, 8, 12, 40, 0)
                .toInstant(ZoneOffset.UTC);
        final RefundCallbackRecord record = new RefundCallbackRecord(6L, 5L,
                CallbackType.REFUND, "PAY1", "RF1", GatewayResult.SUCCESS,
                "RF-TXN-1", 8000L, occurred);
        final RefundCallbackLogPO po = new RefundCallbackRecordConvertor().toPO(record);
        final RefundCallbackRecord back = new RefundCallbackRecordConvertor().toDomain(po);
        Assertions.assertEquals(occurred, back.getOccurredAt());
        Assertions.assertEquals("RF1", back.getRefundNo());
        Assertions.assertEquals(6L, back.getId());
    }

    @Test
    @DisplayName("运单：in_transit 派生位（!= DELIVERED → TRUE / 签收 → NULL）+ 轨迹装载")
    void waybill_inTransitDerivation() {
        final WaybillTrack track = new WaybillTrack(8L, 7L, WaybillStatus.SHIPPED,
                LocalDateTime.of(2026, 9, 8, 14, 0, 0), "已揽收");
        final Waybill shipped = new Waybill(7L, 6666L, "顺丰速运", "SF123", WaybillStatus.SHIPPED,
                List.of(track));
        final WaybillPO po = WAYBILL.convertToPO(shipped);
        Assertions.assertEquals(Boolean.TRUE, po.getInTransit(), "在途态 → TRUE");

        final Waybill delivered = new Waybill(9L, 6666L, "顺丰速运", "SF456",
                WaybillStatus.DELIVERED, List.of(new WaybillTrack(10L, 9L,
                WaybillStatus.DELIVERED, LocalDateTime.of(2026, 9, 9, 9, 0, 0), null)));
        Assertions.assertNull(WAYBILL.convertToPO(delivered).getInTransit(),
                "签收态 → NULL（释放锚点，历史行承载）");

        final Waybill back = WAYBILL.convertToRoot(po, List.of(
                new WaybillTrackConvertor().toPO(track)));
        Assertions.assertEquals(WaybillStatus.SHIPPED, back.getStatus());
        Assertions.assertEquals(1, back.getTracks().size());
        Assertions.assertEquals("已揽收", back.getTracks().get(0).getDescription());
    }

    @Test
    @DisplayName("运单轨迹：occurredAt LocalDateTime 直映射零转换")
    void waybillTrack_directMapping() {
        final WaybillTrack track = new WaybillTrack(8L, 7L, WaybillStatus.IN_TRANSIT,
                LocalDateTime.of(2026, 9, 8, 18, 0, 0), null);
        final WaybillTrackPO po = new WaybillTrackConvertor().toPO(track);
        Assertions.assertEquals(LocalDateTime.of(2026, 9, 8, 18, 0, 0), po.getOccurredAt(),
                "领域即 LocalDateTime，直映射");
        final WaybillTrack back = new WaybillTrackConvertor().toDomain(po);
        Assertions.assertEquals(WaybillStatus.IN_TRANSIT, back.getStatus());
        Assertions.assertEquals(7L, back.getWaybillId());
        Assertions.assertNull(back.getDescription());
    }
}