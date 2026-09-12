package com.nona.inf.persistence.repository;

import com.nona.acceptance.AcceptanceDbSupport;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.domain.logistics.repo.WaybillRepository;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.repo.PaymentOrderRepository;
import com.nona.domain.payment.repo.RefundOrderRepository;
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
import com.nona.domain.payment.ports.CallbackType;
import com.nona.domain.payment.ports.GatewayResult;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.persistence.po.order.MasterOrderPO;
import com.nona.inf.persistence.po.order.OrderItemPO;
import com.nona.inf.persistence.po.order.SubOrderPO;
import com.nona.inf.persistence.po.payment.PaymentOrderPO;
import com.nona.inf.persistence.repository.jpa.MasterOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.OrderItemJpaRepository;
import com.nona.inf.persistence.repository.jpa.PaymentOrderJpaRepository;
import com.nona.inf.persistence.repository.jpa.SubOrderJpaRepository;
import com.nona.inf.timeout.TimeoutType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仓储契约冒烟测试（冒烟清单 6 项）：@SpringBootTest 全上下文 +
 * MySQL 真库（localtunnel 隧道 + -Pfull -Dspring.profiles.active=test），
 * 注入 5 个 DifferRepository 仓储 bean——mock 测不到的装配面逐面验证：
 * 1) claim 条件更新真库语义（4 断言×2 表）；2) clear 幂等 + 不再被
 * findDue 命中；3) 租户 fail-closed（跨店 getByID=null、跨店分页空页）；
 * 4) 差异追踪链路（装载→变更→save→重载一致、从表插行）；5) 查询契约
 * 分页形状（排序/多值过滤/count/空集=全量）；6) waybill 终态装载面。
 * <p>
 * 装配纪律：断言对齐 TradingPoFoundationAcTest 先例——TrackingContext
 * withScope（租户 fail-closed 由 Hibernate @TenantId 过滤器承载）、
 * AtomicLong 主键种子（避开既有数据面）、领域构造器 + 仓储 save（变更
 * 追踪全链路）为主、JPA PO 直写为辅（超时 SQL 面三列领域不承载，仓库
 * 直写补数）。上下文完整启动由本类加载本身 + EcommerceApplicationAcTest
 * 共同验证（无 missing-bean）。
 *
 * @author nona9961
 */
@SpringBootTest
class RepoContractAcTest {

    /**
     * 测试主键种子（自增，避开 TradingPoFoundationAcTest 的 100 万段）
     */
    private static final AtomicLong IDS = new AtomicLong(2_000_000L);

    /**
     * 本类独占 id 段边界（与 {@link #IDS} 同面：2,000,000-2,999,999）——
     * {@link #cleanupOwnedRows} 段位清理谓词；全测试树无其他类使用本段
     * （grep 实证），段位 DELETE 零越界风险。
     */
    private static final long ID_SEGMENT_LOW = 2_000_000L;
    private static final long ID_SEGMENT_HIGH = 2_999_999L;

    /**
     * 店铺 A 租户（数据归属面）
     */
    private static final String SHOP_A = "1001";

    /**
     * 店铺 B 租户（跨店 fail-closed 断言面）
     */
    private static final String SHOP_B = "1002";

    /**
     * 归属店铺业务键（与 SHOP_A 租户一致）
     */
    private static final long SHOP_ID = 1001L;

    @Autowired
    private MasterOrderRepository masterOrderRepository;

    @Autowired
    private SubOrderRepository subOrderRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private RefundOrderRepository refundOrderRepository;

    @Autowired
    private WaybillRepository waybillRepository;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private SubOrderJpaRepository subOrderJpa;

    @Autowired
    private PaymentOrderJpaRepository paymentOrderJpa;

    @Autowired
    private MasterOrderJpaRepository masterOrderJpa;

    @Autowired
    private OrderItemJpaRepository orderItemJpa;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 递增生成测试主键。
     *
     * @return 测试主键
     */
    private static long nextId() {
        return IDS.incrementAndGet();
    }

    /**
     * 判断列表主键严格倒序（分页排序断言辅助）。
     *
     * @param orders 订单列表
     * @return true = 严格倒序
     */
    private static boolean listIsIdDescending(List<MasterOrder> orders) {
        for (int i = 1; i < orders.size(); i++) {
            if (orders.get(i - 1).getId() <= orders.get(i).getId()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断列表主键严格升序（库存分页排序断言辅助）。
     *
     * @param items 库存列表
     * @return true = 严格升序
     */
    private static boolean listIsIdAscending(List<InventoryItem> items) {
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i - 1).getId() >= items.get(i).getId()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造子订单（9 参装载构造：显式状态；金额与条目小计自洽）。
     *
     * @param id          子单主键
     * @param masterOrderId 归属主单
     * @param subOrderNo  子单号
     * @param status      履约状态
     * @param itemSubtotal 订单项小计（分）
     * @return 子订单聚合
     */
    private static SubOrder newSubOrder(long id, long masterOrderId, String subOrderNo,
                                        SubOrderStatus status, long itemSubtotal) {
        final OrderItem item = new OrderItem(42L, 4242L, "经典款 T 恤", itemSubtotal, 1,
                itemSubtotal, "/files/p42.png", "颜色:黑,尺码:M", Map.of("颜色", "黑"), Map.of());
        return new SubOrder(id, masterOrderId, SHOP_ID, subOrderNo + "-" + id,
                new AddressSnapshot("李四", "13900000000", "浙江省", "杭州市", "西湖区", "XX 街 2 号"),
                new AmountDetail(itemSubtotal, 0L, 0L, itemSubtotal),
                List.of(item), status, null);
    }

    /**
     * 构造主订单（装载构造：显式状态；子单引用集合按调用方传入）。
     *
     * @param id          主单主键
     * @param orderNo     订单号
     * @param buyerId     归属买家
     * @param status      整体状态
     * @param subOrderIds 子单引用 ID 集合（须真实存在行，反查面）
     * @return 主订单聚合
     */
    private static MasterOrder newMasterOrder(long id, String orderNo, long buyerId,
                                              MasterOrderStatus status, List<Long> subOrderIds) {
        return new MasterOrder(id, orderNo + "-" + id, buyerId,
                new AddressSnapshot("张三", "13800000000", "上海市", "上海市", "浦东新区", "XX 路 1 号"),
                new AmountDetail(5000L, 600L, 0L, 5600L),
                subOrderIds, List.of(), status);
    }

    /**
     * 构造支付单（创建构造：待支付定型 + 未来截止时间）。
     *
     * @param id     支付单主键
     * @param payNo  支付单号
     * @param orderId 关联主单
     * @param amount 金额（分）
     * @return 支付单聚合
     */
    private static PaymentOrder newPaymentOrder(long id, String payNo, long orderId, long amount) {
        return new PaymentOrder(id, payNo, orderId, amount, "MOCK",
                Instant.now().plusSeconds(1800));
    }

    /**
     * 构造运单（创建/装载共用构造：末条轨迹状态与当前状态一致）。
     *
     * @param id        运单主键
     * @param subOrderId 关联子单
     * @param status    状态
     * @param desc      初始轨迹描述
     * @return 运单聚合
     */
    private static Waybill newWaybill(long id, long subOrderId, WaybillStatus status, String desc) {
        final WaybillTrack track = new WaybillTrack(nextId(), id, status,
                LocalDateTime.now(ZoneOffset.UTC), desc);
        return new Waybill(id, subOrderId, "顺丰速运", "SF" + id, status, List.of(track));
    }

    @Test
    @DisplayName("冒烟-1 claim 条件更新真库语义：1 行成功/重复 false/状态迁移 false/行不存在 false")
    void claimTimeout_conditionUpdateRealDbSemantics() {
        final long subId = nextId();
        final long payId = nextId();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            insertDueSubOrderRow(subId, SubOrderStatus.PAID);
            insertDuePaymentRow(payId, PaymentOrderStatus.PENDING_PAYMENT);

            // ① 候选（status=预期态 且 timeout_at<=now）：claim 命中 1 行
            Assertions.assertTrue(
                    subOrderRepository.claimTimeout(subId, SubOrderStatus.PAID),
                    "履约候选 claim 必须命中 1 行");
            Assertions.assertTrue(
                    paymentOrderRepository.claimTimeout(payId, PaymentOrderStatus.PENDING_PAYMENT),
                    "支付候选 claim 必须命中 1 行");
            // ② claimed=1 后重复 claim 条件不满足
            Assertions.assertFalse(
                    subOrderRepository.claimTimeout(subId, SubOrderStatus.PAID),
                    "已认领行重复 claim 必须失败");
            Assertions.assertFalse(
                    paymentOrderRepository.claimTimeout(payId, PaymentOrderStatus.PENDING_PAYMENT),
                    "已认领支付单重复 claim 必须失败");
            // ③ 状态已迁移（PAID→SHIPPED）后 claim 复查失败
            moveSubOrderStatus(subId, SubOrderStatus.SHIPPED);
            Assertions.assertFalse(
                    subOrderRepository.claimTimeout(subId, SubOrderStatus.PAID),
                    "状态迁移后 claim 必须失败");
            // ④ 行不存在 claim 失败
            Assertions.assertFalse(
                    subOrderRepository.claimTimeout(nextId(), SubOrderStatus.PAID),
                    "不存在的目标 claim 必须失败");
            Assertions.assertFalse(
                    paymentOrderRepository.claimTimeout(nextId(),
                            PaymentOrderStatus.PENDING_PAYMENT),
                    "不存在的支付单 claim 必须失败");
            // DB 面：claimed 位落库
            final Boolean claimed = jdbcTemplate.queryForObject(
                    "SELECT claimed FROM sub_order WHERE id = ?", Boolean.class, subId);
            Assertions.assertEquals(Boolean.TRUE, claimed, "claimed 位必须落库为 1");
        });
    }

    @Test
    @DisplayName("冒烟-2 clear 幂等：清空三列、不再被 findDue 命中、重复与不存在均无异常")
    void clearTimeoutDeadline_idempotent_andNoLongerScanned() {
        final long subId = nextId();
        final long payId = nextId();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            insertDueSubOrderRow(subId, SubOrderStatus.PAID);
            insertDuePaymentRow(payId, PaymentOrderStatus.PENDING_PAYMENT);

            final Instant now = Instant.now();
            Assertions.assertFalse(subOrderRepository
                            .findDueByStatusAndTimeoutAtBefore(SubOrderStatus.PAID, now, 10)
                            .isEmpty(),
                    "clear 前 findDue 必须命中到期候选");
            Assertions.assertFalse(paymentOrderRepository
                            .findDueByStatusAndTimeoutAtBefore(
                                    PaymentOrderStatus.PENDING_PAYMENT, now, 10)
                            .isEmpty(),
                    "clear 前支付 findDue 必须命中到期候选");

            subOrderRepository.clearTimeoutDeadline(subId);
            paymentOrderRepository.clearTimeoutDeadline(payId);

            final Map<String, Object> subRow = jdbcTemplate.queryForMap(
                    "SELECT timeout_at, timeout_type, claimed FROM sub_order WHERE id = ?", subId);
            Assertions.assertNull(subRow.get("timeout_at"), "clear 后截止时间必须为 NULL");
            Assertions.assertNull(subRow.get("timeout_type"), "clear 后超时类型必须为 NULL");
            Assertions.assertEquals(Boolean.FALSE, subRow.get("claimed"), "clear 后认领位必须归零");
            // 行不再被扫描命中（timeout_at=NULL 不在 <= now 面）
            Assertions.assertTrue(subOrderRepository
                            .findDueByStatusAndTimeoutAtBefore(SubOrderStatus.PAID, now, 10)
                            .stream().noneMatch(due -> due.getId().equals(subId)),
                    "clear 后行不得再被 findDue 命中");
            Assertions.assertTrue(paymentOrderRepository
                            .findDueByStatusAndTimeoutAtBefore(
                                    PaymentOrderStatus.PENDING_PAYMENT, now, 10)
                            .stream().noneMatch(due -> due.getId().equals(payId)),
                    "clear 后支付行不得再被 findDue 命中");
            // 幂等：重复 clear 与不存在的目标均无异常（0 行成功）
            Assertions.assertDoesNotThrow(() ->
                    subOrderRepository.clearTimeoutDeadline(subId));
            Assertions.assertDoesNotThrow(() ->
                    subOrderRepository.clearTimeoutDeadline(nextId()));
            Assertions.assertDoesNotThrow(() ->
                    paymentOrderRepository.clearTimeoutDeadline(nextId()));
        });
    }

    @Test
    @DisplayName("冒烟-3 租户 fail-closed：跨店 getByID=null、跨店分页空页")
    void tenantFailClosed_crossShopReadsAbsent() {
        final long subId = nextId();
        final long itemId = nextId();
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            final SubOrder sub = newSubOrder(subId, nextId(), "SUB-TENANT-A",
                    SubOrderStatus.PAID, 8800L);
            Assertions.assertTrue(subOrderRepository.save(sub), "子单首次落库必须成功");
            subOrderJpa.save(subOrderRowFor(itemId, nextId(), SubOrderStatus.PAID));
        });
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_B);
            Assertions.assertNull(subOrderRepository.getByID(subId),
                    "跨店按 ID 装载必须按不存在呈现（fail-closed）");
            Assertions.assertTrue(subOrderRepository
                            .listPagedByShop(SHOP_ID, null, 0, 20).isEmpty(),
                    "跨店商家分页必须空页（shop_id 条件 + 租户过滤双层防线）");
            Assertions.assertEquals(0L, subOrderRepository.countByShop(SHOP_ID, null),
                    "跨店商家计数必须为 0");
        });
    }

    @Test
    @DisplayName("冒烟-4 差异追踪链路：子单装载→状态推进→save→重载一致（含从表插行）")
    void diffTracking_subOrder_advanceAndReloadConsistent() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            final long subId = nextId();
            final SubOrder sub = newSubOrder(subId, nextId(), "SUB-DIFF-A",
                    SubOrderStatus.PENDING_PAYMENT, 8800L);
            Assertions.assertTrue(subOrderRepository.save(sub), "子单 doInsert 根行+条目行");

            final SubOrder loaded = subOrderRepository.getByID(subId);
            Assertions.assertNotNull(loaded, "装载必须命中");
            Assertions.assertEquals(1, loaded.getItems().size(), "从表条目随聚合内存装配");
            Assertions.assertEquals(SubOrderStatus.PENDING_PAYMENT, loaded.getStatus());
            Assertions.assertEquals(1, orderItemJpa.findBySubOrderIdOrderByIdAsc(subId).size(),
                    "订单项从表落库 1 行");

            loaded.markPaid();
            Assertions.assertTrue(subOrderRepository.save(loaded), "状态推进变更集落库");
            final SubOrder reloaded = subOrderRepository.getByID(subId);
            Assertions.assertEquals(SubOrderStatus.PAID, reloaded.getStatus(),
                    "根行字段变更后重载一致");
            Assertions.assertEquals(1, reloaded.getItems().size(), "条目行不因根行更新重复");
        });
    }

    @Test
    @DisplayName("冒烟-5 差异追踪链路：支付单留痕追加插行 + 根行迁移，重载一致")
    void diffTracking_paymentOrder_callbackAppendAndMigrate() {
        TrackingContext.withScope(() -> {
            final long payId = nextId();
            final PaymentOrder pay = newPaymentOrder(payId, "PAY-SMOKE-" + payId,
                    nextId(), 5600L);
            Assertions.assertTrue(paymentOrderRepository.save(pay), "支付单 doInsert 根行");

            final PaymentCallbackRecord record = new PaymentCallbackRecord(nextId(), payId,
                    CallbackType.PAY, pay.getPayNo(), null, GatewayResult.SUCCESS,
                    "TXN-SMOKE-" + payId, 5600L, Instant.now());
            pay.appendCallbackRecord(record);
            pay.markPaid("TXN-SMOKE-" + payId, 5600L);
            Assertions.assertTrue(paymentOrderRepository.save(pay),
                    "留痕追加 + 根行迁移变更集落库");

            final PaymentOrder reloaded = paymentOrderRepository.getByID(payId);
            Assertions.assertEquals(PaymentOrderStatus.PAID, reloaded.getStatus(),
                    "根行状态迁移后重载一致");
            Assertions.assertEquals(1, reloaded.getCallbacks().size(), "留痕追加插行随聚合重载");
            Assertions.assertEquals("TXN-SMOKE-" + payId, reloaded.getChannelTxnNo(),
                    "渠道流水号落库往返");
        });
    }

    @Test
    @DisplayName("冒烟-6 差异追踪链路：运单推进追加轨迹行 + 在途位派生，重载一致")
    void diffTracking_waybill_advanceAppendTrack() {
        TrackingContext.withScope(() -> {
            final long waybillId = nextId();
            final Waybill waybill = newWaybill(waybillId, nextId(), WaybillStatus.SHIPPED,
                    "已揽收");
            Assertions.assertTrue(waybillRepository.save(waybill), "运单 doInsert 根行+初始轨迹");

            final WaybillTrack track = new WaybillTrack(nextId(), waybillId,
                    WaybillStatus.IN_TRANSIT, LocalDateTime.now(ZoneOffset.UTC), "运输中");
            waybill.advanceTo(track);
            Assertions.assertTrue(waybillRepository.save(waybill), "推进变更集落库");

            final Waybill reloaded = waybillRepository.getByID(waybillId);
            Assertions.assertEquals(WaybillStatus.IN_TRANSIT, reloaded.getStatus(),
                    "推进后重载一致");
            Assertions.assertEquals(2, reloaded.getTracks().size(), "轨迹 append-only 追加插行");
            Assertions.assertTrue(reloaded.isInTransit(), "在途位随状态派生");
        });
    }

    @Test
    @DisplayName("冒烟-7 查询契约：主单买家分页（排序/多值过滤/count/空集=全量）")
    void queryPaging_masterOrder_buyerShape() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            // 引用 ID 协作：主单必须配真实子单行（反查面非空）
            final long orderA = nextId();
            final long orderB = nextId();
            final long subA = nextId();
            final long subB = nextId();
            subOrderJpa.save(subOrderRowFor(subA, orderA, SubOrderStatus.PAID));
            subOrderJpa.save(subOrderRowFor(subB, orderB, SubOrderStatus.PAID));
            Assertions.assertTrue(masterOrderRepository.save(newMasterOrder(
                    orderA, "ORD-SMOKE-A", 9001L, MasterOrderStatus.PAID, List.of(subA))));
            Assertions.assertTrue(masterOrderRepository.save(newMasterOrder(
                    orderB, "ORD-SMOKE-B", 9001L, MasterOrderStatus.COMPLETED, List.of(subB))));

            // 全量：排序 create_time DESC, id DESC（同事务批量 create_time 相同 → id tie-break）
            final List<MasterOrder> all = masterOrderRepository.listPagedByBuyer(
                    9001L, null, 0, 50);
            Assertions.assertEquals(masterOrderRepository.countByBuyer(9001L, null),
                    all.size(), "null 状态 = 全量（list/count 一致）");
            Assertions.assertTrue(all.stream().anyMatch(order -> order.getId() == orderA)
                            && all.stream().anyMatch(order -> order.getId() == orderB),
                    "本方法两主单在全量结果中");
            Assertions.assertTrue(listIsIdDescending(all),
                    "全量排序必须为 id 倒序（业务时间倒序 + 主键 tie-breaker）");
            // 空集合同样 = 全量（「全部」tab 承载面，与 null 等义）
            Assertions.assertEquals(all.size(),
                    masterOrderRepository.listPagedByBuyer(9001L, List.of(), 0, 50).size(),
                    "空状态集合 = 不过滤");
            // 状态多值过滤 + count 一致
            final List<MasterOrder> paidOnly = masterOrderRepository.listPagedByBuyer(
                    9001L, Set.of(MasterOrderStatus.PAID), 0, 50);
            Assertions.assertTrue(paidOnly.stream().allMatch(order ->
                            order.getStatus() == MasterOrderStatus.PAID),
                    "多值过滤只含 PAID");
            Assertions.assertTrue(
                    paidOnly.stream().anyMatch(order -> order.getId() == orderA),
                    "本方法 PAID 主单命中");
            Assertions.assertEquals(masterOrderRepository.countByBuyer(
                    9001L, Set.of(MasterOrderStatus.PAID)), paidOnly.size(),
                    "count 与过滤一致");
            Assertions.assertEquals(masterOrderRepository.countByBuyer(9001L, null),
                    masterOrderRepository.countByBuyer(9001L, List.of()),
                    "count 空集 = 全量");
        });
    }

    @Test
    @DisplayName("冒烟-8 查询契约：子单商家分页（shopId+状态多值+count）+ 库存分页（id 升序/租户隔离）")
    void queryPaging_subOrderShopAndInventoryShape() {
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_A);
            final long subA = nextId();
            final long subB = nextId();
            Assertions.assertTrue(subOrderRepository.save(newSubOrder(
                    subA, nextId(), "SUB-PAGE-A", SubOrderStatus.REFUNDING, 1000L)));
            Assertions.assertTrue(subOrderRepository.save(newSubOrder(
                    subB, nextId(), "SUB-PAGE-B", SubOrderStatus.COMPLETED, 2000L)));

            final List<SubOrder> refunding = subOrderRepository.listPagedByShop(
                    SHOP_ID, List.of(SubOrderStatus.REFUNDING), 0, 20);
            Assertions.assertEquals(1, refunding.size(), "商家分页状态多值过滤");
            Assertions.assertEquals(subA, refunding.get(0).getId());
            Assertions.assertEquals(1L, subOrderRepository.countByShop(
                    SHOP_ID, List.of(SubOrderStatus.REFUNDING)));
            final List<SubOrder> allShop = subOrderRepository.listPagedByShop(
                    SHOP_ID, null, 0, 50);
            Assertions.assertEquals(subOrderRepository.countByShop(SHOP_ID, null),
                    allShop.size(), "null 状态 = 全量（list/count 一致）");
            Assertions.assertTrue(allShop.stream().anyMatch(sub -> sub.getId() == subA)
                            && allShop.stream().anyMatch(sub -> sub.getId() == subB),
                    "本方法两子单在全量结果中");
            Assertions.assertEquals(subOrderRepository.countByShop(SHOP_ID, null),
                    subOrderRepository.countByShop(SHOP_ID, List.of()),
                    "count 空集 = 全量");

            // 库存分页：id ASC + count + 订单项从表独立
            final long itemA = nextId();
            final long itemB = nextId();
            Assertions.assertTrue(inventoryItemRepository.save(
                    new InventoryItem(itemA, SHOP_ID, 5001L, 10, 0, 0, 0)));
            Assertions.assertTrue(inventoryItemRepository.save(
                    new InventoryItem(itemB, SHOP_ID, 5002L, 20, 0, 0, 0)));
            final List<InventoryItem> items = inventoryItemRepository.listPaged(0, 50);
            Assertions.assertEquals(inventoryItemRepository.count(), items.size(),
                    "店铺库存 list/count 一致");
            Assertions.assertTrue(items.stream().anyMatch(item -> item.getId() == itemA)
                            && items.stream().anyMatch(item -> item.getId() == itemB),
                    "本方法两库存行在分页结果中");
            Assertions.assertTrue(listIsIdAscending(items), "库存分页按主键升序");
        });
        TrackingContext.withScope(() -> {
            TrackingContext.scope().setTenantID(SHOP_B);
            Assertions.assertTrue(inventoryItemRepository.listPaged(0, 10).isEmpty(),
                    "跨店库存分页必须空页（租户过滤 fail-closed）");
            Assertions.assertEquals(0L, inventoryItemRepository.count(),
                    "跨店库存计数必须为 0");
        });
    }

    @Test
    @DisplayName("冒烟-9 运单按子单装载：签收终态行可装载；历史并存取最新（主键倒序）")
    void waybill_findBySubOrderId_terminalAndLatest() {
        TrackingContext.withScope(() -> {
            // 终态展示面：仅一张已签收运单也要能经 findBySubOrderId 装载
            // （在途锚点不含签收行——不变量与展示面在契约层显式分离）
            final long subId = nextId();
            final long deliveredId = nextId();
            final Waybill delivered = newWaybill(deliveredId, subId, WaybillStatus.DELIVERED,
                    "已签收");
            Assertions.assertTrue(waybillRepository.save(delivered), "签收运单落库");
            Assertions.assertTrue(
                    waybillRepository.findInTransitBySubOrderId(subId).isEmpty(),
                    "签收终态不在在途锚点");
            final Waybill loadedTerminal =
                    waybillRepository.findBySubOrderId(subId).orElseThrow(
                            () -> new AssertionError("签收终态运单展示面装载失败"));
            Assertions.assertEquals(deliveredId, loadedTerminal.getId(), "终态行装载命中");

            // 历史并存：在途新行存在时展示面取最新一张（主键倒序第一条）
            final long inTransitId = nextId();
            final Waybill inTransit = newWaybill(inTransitId, subId, WaybillStatus.IN_TRANSIT,
                    "运输中");
            Assertions.assertTrue(waybillRepository.save(inTransit), "在途新行落库");
            final Waybill latest = waybillRepository.findBySubOrderId(subId).orElseThrow(
                    () -> new AssertionError("最新运单展示面装载失败"));
            Assertions.assertEquals(inTransitId, latest.getId(),
                    "历史并存行取主键倒序第一条（最新）");
        });
    }

    /**
     * 直写一条到期子单行（超时 SQL 面三列领域不承载，PO 直写补数）。
     *
     * @param subId  子单主键
     * @param status 状态
     */
    private void insertDueSubOrderRow(long subId, SubOrderStatus status) {
        final SubOrderPO po = subOrderRowFor(subId, nextId(), status);
        po.setTimeoutAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        po.setTimeoutType(TimeoutType.ORDER_SHIP);
        subOrderJpa.save(po);
        final OrderItemPO item = new OrderItemPO();
        item.setId(nextId());
        item.setSubOrderId(subId);
        item.setProductId(42L);
        item.setSkuId(nextId());
        item.setProductName("经典款 T 恤");
        item.setUnitPrice(1000L);
        item.setQuantity(1);
        item.setSubtotal(1000L);
        item.setMainImageUrl("/files/p42.png");
        item.setSpecSummary("颜色:黑,尺码:M");
        item.setSpecAttributes("{\"颜色\":\"黑\"}");
        item.setCustomAttributes("{}");
        orderItemJpa.save(item);
    }

    /**
     * 直写一条到期支付单行（超时 SQL 面三列领域不承载，PO 直写补数）。
     *
     * @param payId  支付单主键
     * @param status 状态
     */
    private void insertDuePaymentRow(long payId, PaymentOrderStatus status) {
        final PaymentOrderPO po = new PaymentOrderPO();
        po.setId(payId);
        po.setPayNo("PAY-SMOKE-DUE-" + payId);
        po.setOrderId(nextId());
        po.setAmount(5600L);
        po.setChannel("MOCK");
        po.setTimeoutAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        po.setStatus(status);
        paymentOrderJpa.save(po);
    }

    /**
     * 直写子单行（冒烟数据补数面：分页引用/跨店面）。
     *
     * @param subId  子单主键
     * @param status 状态
     * @return 子单 PO
     */
    private SubOrderPO subOrderRowFor(long subId, long masterOrderId, SubOrderStatus status) {
        final SubOrderPO po = new SubOrderPO();
        po.setId(subId);
        po.setMasterOrderId(masterOrderId);
        po.setShopId(SHOP_ID);
        po.setSubOrderNo("SUB-SMOKE-ROW-" + subId);
        po.setRecipient("王五");
        po.setPhone("13700000000");
        po.setProvince("北京市");
        po.setCity("北京市");
        po.setDistrict("朝阳区");
        po.setDetail("XX 大道 3 号");
        po.setGoodsAmount(1000L);
        po.setFreightAmount(0L);
        po.setDiscount(0L);
        po.setPaidAmount(1000L);
        po.setStatus(status);
        return po;
    }

    /**
     * 直接迁移子单行状态（状态迁移后 claim 复查失败断言的数据面）。
     *
     * @param subId  子单主键
     * @param status 新状态
     */
    private void moveSubOrderStatus(long subId, SubOrderStatus status) {
        final SubOrderPO po = subOrderJpa.findById(subId).orElseThrow();
        po.setStatus(status);
        subOrderJpa.save(po);
    }

    /**
     * 类级段位清理（2026-09-11 补，测试库卫生 WU 第二部分——类级兜底）：
     * ecommerce_test 由 app（dev demo）与本 AcTest 共享，本类直插/经仓储
     * 落库行无清理时每轮 -Pfull 留下大量孤儿行——订单类残留被 app
     * TimeoutScheduler 拾取处理（app.log 实证 WARN taskId=1000006）、孤儿
     * 运单被 app 推进产生无主事件。本方法按本类独占 id 段幂等 DELETE（从表
     * 先删、主表后删；段位谓词不存在即 0 行天然幂等），覆盖全部冒烟
     * （冒烟-1~9）。order_item 按 sub_order_id 段删——经 diff 链路落的条目
     * 行 id 由 IDUtils.generateID() 雪花生成、不在测试 id 段内
     * （SubOrderRepositoryImpl.insertItemRow），按根行关联列段删才完整覆盖
     * （本类直写路径的条目行 id 段内、diff 链路路径段外，两源同覆盖）。
     * refund_callback_log / refund_order 当前无落行（refundOrderRepository
     * 仅注入未调用），语句保留作姿态防御（幂等 0 行，未来 refund 冒烟自动
     * 进入兜底面）。与 TradingPoFoundationAcTest.cleanupOwnedRows 同模式
     * （同表集 + inventory_item）。
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
            AcceptanceDbSupport.update(mysql,
                    "DELETE FROM inventory_item WHERE id BETWEEN ? AND ?",
                    ID_SEGMENT_LOW, ID_SEGMENT_HIGH);
        }
    }
}