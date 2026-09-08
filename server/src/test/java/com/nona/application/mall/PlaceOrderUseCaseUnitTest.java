package com.nona.application.mall;

import com.nona.api.mall.EstimateRequest;
import com.nona.api.mall.EstimateResult;
import com.nona.api.mall.OrderResult;
import com.nona.api.mall.PlaceOrderRequest;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.ports.FreightCalculator;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.AccountType;
import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.domain.identity.repo.AccountRepository;
import com.nona.domain.identity.repo.AddressBookRepository;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.MasterOrderStatus;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.domain.order.factory.CartFactory;
import com.nona.domain.order.factory.MasterOrderFactory;
import com.nona.domain.order.factory.SubOrderFactory;
import com.nona.domain.order.repo.CartRepository;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.exceptions.BusinessException;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.timeout.TimeoutType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单编排用例单元测试：结算试算与提交订单的编排契约（B7.1-B7.6/TD-10
 * 拆单）——勾选集消费/跨店铺拆单/运费与金额明细/库存预占/支付单创建与
 * 超时注册，以及买家/地址/在售/归属校验的失败路径。
 * <p>
 * 依赖装配：机械层（工厂/枚举）用真实对象；业务端口以内存桩承载
 * （账号/地址簿/购物车/商品门面/计算器/主单与子单仓储），库存门面与
 * 支付端口以 mock 承载调用断言；提权事务以 mock 直执行（单测无事务
 * 管理器——事务边界属应用层，由用例注解与提权包装承载，绿期集成测试
 * 验证真实回滚）。
 *
 * @author nona9961
 */
class PlaceOrderUseCaseUnitTest {

    /**
     * 测试买家（认证上下文传入的身份）
     */
    private static final long BUYER_ID = 10001L;

    /**
     * 地址簿主键与目标地址（归属当前买家）
     */
    private static final long BOOK_ID = 7001L;
    private static final long ADDRESS_ID = 8001L;
    private static final long OTHER_ADDRESS_ID = 8002L;

    /**
     * 商品（A/B/C 分属店铺甲/乙/丙）
     */
    private static final long PRODUCT_A = 2001L;
    private static final long PRODUCT_B = 2002L;
    private static final long PRODUCT_C = 2003L;

    /**
     * SKU（A1/A2 店铺甲，B1 店铺乙，C1 店铺丙）
     */
    private static final long SKU_A1 = 3001L;
    private static final long SKU_A2 = 3002L;
    private static final long SKU_B1 = 3101L;
    private static final long SKU_C1 = 3201L;

    /**
     * 店铺（分组/拆单锚点）
     */
    private static final long SHOP_A = 4001L;
    private static final long SHOP_B = 4002L;
    private static final long SHOP_C = 4003L;

    /**
     * 运费模板 ID（A：满额免邮；B：按件；C：包邮）
     */
    private static final long TEMPLATE_A = 6001L;
    private static final long TEMPLATE_B = 6002L;
    private static final long TEMPLATE_C = 6003L;

    /**
     * 测试购物车主键
     */
    private static final long CART_ID = 5001L;

    /**
     * 支付单桩返回（payNo/截止时间固定，断言编排只断言关键字段与调用参数）
     */
    private static final PendingPayment STUB_PAYMENT = new PendingPayment(
            9001L, "PAY20260906000000000001", 0L,
            TimeoutType.ORDER_PAY.durationMillis(), Instant.parse("2026-09-06T12:30:00Z"));

    /* ---------- 被测装配 ---------- */

    private StubAccountRepository accountRepository;
    private StubAddressBookRepository addressBookRepository;
    private StubCartRepository cartRepository;
    private StubProductQueryFacade queryFacade;
    private StubFreightCalculator freightCalculator;
    private MasterOrderFactory masterOrderFactory;
    private SubOrderFactory subOrderFactory;
    private StubMasterOrderRepository masterOrderRepository;
    private StubSubOrderRepository subOrderRepository;
    private InventoryFacade inventoryFacade;
    private PaymentPort paymentPort;
    private TenantPrivilege tenantPrivilege;
    private TransactionTemplate transactionTemplate;
    private PlaceOrderUseCase useCase;

    /**
     * 每用例前重建桩与用例实例（用例无状态，桩隔离各测试数据）。
     */
    @BeforeEach
    void setUp() {
        accountRepository = new StubAccountRepository();
        addressBookRepository = new StubAddressBookRepository();
        cartRepository = new StubCartRepository(new CartFactory());
        queryFacade = new StubProductQueryFacade();
        freightCalculator = new StubFreightCalculator();
        masterOrderFactory = new MasterOrderFactory();
        subOrderFactory = new SubOrderFactory();
        masterOrderRepository = new StubMasterOrderRepository();
        subOrderRepository = new StubSubOrderRepository();
        inventoryFacade = mock(InventoryFacade.class);
        paymentPort = mock(PaymentPort.class);
        transactionTemplate = mock(TransactionTemplate.class);
        tenantPrivilege = mock(TenantPrivilege.class);
        try {
            when(tenantPrivilege.elevatedInTransaction(eq(transactionTemplate), any(Callable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, Callable.class).call());
        } catch (final Exception e) {
            throw new IllegalStateException("提权事务 stub 装配失败", e);
        }
        when(paymentPort.createPendingPayment(anyLong(), anyLong(), anyLong()))
                .thenReturn(STUB_PAYMENT);
        useCase = buildUseCase(freightCalculator);
    }

    /**
     * 组装被测用例（计算器可替换——三规则边界场景切换规则语义桩）。
     *
     * @param calculator 运费计算器
     * @return 用例实例
     */
    private PlaceOrderUseCase buildUseCase(FreightCalculator calculator) {
        return new PlaceOrderUseCase(accountRepository, addressBookRepository, cartRepository,
                queryFacade, calculator, masterOrderFactory, subOrderFactory,
                masterOrderRepository, subOrderRepository, inventoryFacade, paymentPort,
                tenantPrivilege, transactionTemplate);
    }

    /* ================= happy path ================= */

    /**
     * happy：单店铺下单完整链路——勾选集消费 → 试算（金额/运费）→ 子单
     * 快照固化 → 预占（子单 ID 维度）→ 主单落库 → 支付单创建（含超时时
     * 长注册）→ 返回订单视图。
     */
    @Test
    @DisplayName("单店铺下单：金额正确/预占成功/支付单创建/视图完整")
    void placeOrder_singleShop_fullFlow() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(
                entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true),
                entry(PRODUCT_A, SKU_A2, 3, SHOP_A, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                thresholdFreight(TEMPLATE_A, 50000L, 800L),
                skuOf(SKU_A1, 10000L, 10), skuOf(SKU_A2, 5000L, 20)));
        // 规则语义桩缺省返回 0——本用例期望达额判定收取基础运费 800
        // （机械修复：为默认计算器配置模板 A 运费预设，见实现报告缺口登记）
        freightCalculator.setFreight(TEMPLATE_A, 800L);
        // 支付单桩返回金额 = 实付（视图映射断言：用例把支付单金额透传）
        when(paymentPort.createPendingPayment(anyLong(), anyLong(), anyLong()))
                .thenReturn(new PendingPayment(9001L, "PAY20260906000000000001", 35800L,
                        TimeoutType.ORDER_PAY.durationMillis(), Instant.parse("2026-09-06T12:30:00Z")));

        final OrderResult result = useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1, SKU_A2)));

        // 子单：店铺/金额/快照（商品额 = Σ 小计；运费 = 阈值未达基础运费）
        final SubOrder sub = subOrderRepository.lastSaved();
        assertThat(sub.getShopId()).isEqualTo(SHOP_A);
        assertThat(sub.getStatus()).isEqualTo(SubOrderStatus.PENDING_PAYMENT);
        assertThat(sub.getAmount().getGoodsAmount()).isEqualTo(35000L);
        assertThat(sub.getAmount().getFreightAmount()).isEqualTo(800L);
        assertThat(sub.getAmount().getPaidAmount()).isEqualTo(35800L);
        assertThat(sub.getItems()).hasSize(2);
        final OrderItem first = sub.getItems().get(0);
        assertThat(first.getSkuId()).isEqualTo(SKU_A1);
        assertThat(first.getProductName()).isEqualTo("测试商品" + PRODUCT_A);
        assertThat(first.getUnitPrice()).isEqualTo(10000L);
        assertThat(first.getQuantity()).isEqualTo(2);
        assertThat(first.getSubtotal()).isEqualTo(20000L);
        assertThat(first.getMainImageUrl()).isEqualTo("/files/img-" + PRODUCT_A);
        // 地址快照固化（B7.6）
        assertThat(sub.getAddress().getRecipient()).isEqualTo("张三");
        assertThat(sub.getAddress().getPhone()).isEqualTo("13800138000");
        // 子单号非空（TD-13 业务单号）
        assertThat(sub.getSubOrderNo()).isNotBlank();

        // 预占：以子单 ID 为操作单元，明细 = 勾选条目 SKU + 数量
        verify(inventoryFacade).preoccupy(eq(sub.getId()), eq(List.of(
                new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3))));

        // 主单：金额四维 = 子单合计；状态待支付
        final MasterOrder master = masterOrderRepository.lastSaved();
        assertThat(master.getOrderNo()).startsWith("ORD");
        assertThat(master.getBuyerId()).isEqualTo(BUYER_ID);
        assertThat(master.getStatus()).isEqualTo(MasterOrderStatus.PENDING_PAYMENT);
        assertThat(master.getSubOrderIds()).containsExactly(sub.getId());
        assertThat(master.getAmount().getGoodsAmount()).isEqualTo(35000L);
        assertThat(master.getAmount().getFreightAmount()).isEqualTo(800L);
        assertThat(master.getAmount().getDiscount()).isZero();
        assertThat(master.getAmount().getPaidAmount()).isEqualTo(35800L);

        // 支付单创建 + 支付超时注册（支付超时时长 = 订单域规则值）
        verify(paymentPort).createPendingPayment(master.getId(), 35800L,
                TimeoutType.ORDER_PAY.durationMillis());

        // 返回订单视图（主单号/子单列表/支付单）
        assertThat(result.masterOrderId()).isEqualTo(master.getId());
        assertThat(result.orderNo()).isEqualTo(master.getOrderNo());
        assertThat(result.subOrders()).hasSize(1);
        assertThat(result.subOrders().get(0).shopId()).isEqualTo(SHOP_A);
        assertThat(result.subOrders().get(0).shopName()).isEqualTo("店铺甲");
        assertThat(result.subOrders().get(0).goodsAmount()).isEqualTo(35000L);
        assertThat(result.subOrders().get(0).freightAmount()).isEqualTo(800L);
        assertThat(result.subOrders().get(0).paidAmount()).isEqualTo(35800L);
        assertThat(result.payment().payNo()).isEqualTo(STUB_PAYMENT.payNo());
        assertThat(result.payment().amount()).isEqualTo(35800L);
        assertThat(result.payment().timeoutAt()).isNotNull();
    }

    /**
     * happy：跨店铺下单——N 店铺 → N 子单（每店独立运费），预占按子单
     * 拆分逐店执行，主单金额 = Σ 子单（all-or-nothing 编排面）。
     */
    @Test
    @DisplayName("跨店铺下单：N 店 N 子单，预占逐店拆分，主单金额=Σ子单")
    void placeOrder_multiShop_splitsSubOrdersPerShop() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(
                entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true),
                entry(PRODUCT_A, SKU_A2, 3, SHOP_A, true),
                entry(PRODUCT_B, SKU_B1, 1, SHOP_B, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                thresholdFreight(TEMPLATE_A, 50000L, 800L),
                skuOf(SKU_A1, 10000L, 10), skuOf(SKU_A2, 5000L, 20)));
        queryFacade.returns(viewFor(PRODUCT_B, SHOP_B, "店铺乙",
                freeFreight(TEMPLATE_C), skuOf(SKU_B1, 10000L, 5)));
        // 规则语义桩缺省返回 0——本用例期望满额免邮未达额收取基础运费 800
        // （机械修复：为默认计算器配置模板 A 运费预设，见实现报告缺口登记）
        freightCalculator.setFreight(TEMPLATE_A, 800L);

        final OrderResult result = useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1, SKU_A2, SKU_B1)));

        // 两子单：金额与运费按店铺独立装配
        final List<SubOrder> subs = subOrderRepository.savedSubOrders();
        assertThat(subs).hasSize(2);
        final SubOrder subA = subs.get(0);
        final SubOrder subB = subs.get(1);
        assertThat(subA.getShopId()).isEqualTo(SHOP_A);
        assertThat(subA.getAmount().getGoodsAmount()).isEqualTo(35000L);
        assertThat(subA.getAmount().getFreightAmount()).isEqualTo(800L);
        assertThat(subA.getAmount().getPaidAmount()).isEqualTo(35800L);
        assertThat(subB.getShopId()).isEqualTo(SHOP_B);
        assertThat(subB.getAmount().getGoodsAmount()).isEqualTo(10000L);
        assertThat(subB.getAmount().getFreightAmount()).isZero();
        assertThat(subB.getAmount().getPaidAmount()).isEqualTo(10000L);

        // 预占：逐店拆分（各自子单 ID + 各自 SKU 明细）
        verify(inventoryFacade).preoccupy(eq(subA.getId()),
                eq(List.of(new StockChangeItem(SKU_A1, 2), new StockChangeItem(SKU_A2, 3))));
        verify(inventoryFacade).preoccupy(eq(subB.getId()),
                eq(List.of(new StockChangeItem(SKU_B1, 1))));

        // 主单金额恒等式（goods/freight/paid 四维 = Σ 子单）
        final MasterOrder master = masterOrderRepository.lastSaved();
        assertThat(master.getAmount().getGoodsAmount()).isEqualTo(45000L);
        assertThat(master.getAmount().getFreightAmount()).isEqualTo(800L);
        assertThat(master.getAmount().getPaidAmount()).isEqualTo(45800L);
        assertThat(master.getSubOrderIds()).containsExactly(subA.getId(), subB.getId());
        verify(paymentPort).createPendingPayment(master.getId(), 45800L,
                TimeoutType.ORDER_PAY.durationMillis());

        // 视图：两子单（含店铺名 B7.5③）
        assertThat(result.subOrders()).hasSize(2);
        assertThat(result.subOrders()).extracting(OrderResult.SubOrderResult::shopId)
                .containsExactly(SHOP_A, SHOP_B);
        assertThat(result.subOrders()).extracting(OrderResult.SubOrderResult::shopName)
                .containsExactly("店铺甲", "店铺乙");
    }

    /**
     * happy：结算试算——按店铺分组（店铺名/商品额/运费/实付），总计 =
     * Σ 商品金额 + Σ 运费（B7.4②）。
     */
    @Test
    @DisplayName("试算按店铺分组并汇总金额")
    void estimate_groupsByShop_withFreightAndTotals() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        cartRepository.seed(cartWith(
                entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true),
                entry(PRODUCT_A, SKU_A2, 3, SHOP_A, true),
                entry(PRODUCT_B, SKU_B1, 1, SHOP_B, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                thresholdFreight(TEMPLATE_A, 50000L, 800L),
                skuOf(SKU_A1, 10000L, 10), skuOf(SKU_A2, 5000L, 20)));
        queryFacade.returns(viewFor(PRODUCT_B, SHOP_B, "店铺乙",
                freeFreight(TEMPLATE_C), skuOf(SKU_B1, 10000L, 5)));
        // 规则语义桩缺省返回 0——本用例期望满额免邮未达额收取基础运费 800
        // （机械修复：为默认计算器配置模板 A 运费预设，见实现报告缺口登记）
        freightCalculator.setFreight(TEMPLATE_A, 800L);

        final EstimateResult result = useCase.estimate(BUYER_ID,
                new EstimateRequest(List.of(SKU_A1, SKU_A2, SKU_B1)));

        assertThat(result.groups()).hasSize(2);
        final EstimateResult.EstimateGroup groupA = result.groups().get(0);
        assertThat(groupA.shopId()).isEqualTo(SHOP_A);
        assertThat(groupA.shopName()).isEqualTo("店铺甲");
        assertThat(groupA.goodsAmount()).isEqualTo(35000L);
        assertThat(groupA.freightAmount()).isEqualTo(800L);
        assertThat(groupA.discount()).isZero();
        assertThat(groupA.paidAmount()).isEqualTo(35800L);
        assertThat(groupA.items()).extracting(EstimateResult.EstimateItem::skuId)
                .containsExactly(SKU_A1, SKU_A2);
        assertThat(groupA.items()).extracting(EstimateResult.EstimateItem::subtotal)
                .containsExactly(20000L, 15000L);
        final EstimateResult.EstimateGroup groupB = result.groups().get(1);
        assertThat(groupB.shopId()).isEqualTo(SHOP_B);
        assertThat(groupB.freightAmount()).isZero();
        assertThat(groupB.paidAmount()).isEqualTo(10000L);
        assertThat(result.totalGoodsAmount()).isEqualTo(45000L);
        assertThat(result.totalFreightAmount()).isEqualTo(800L);
        assertThat(result.totalDiscount()).isZero();
        assertThat(result.totalPaidAmount()).isEqualTo(45800L);
    }

    /**
     * happy/critical：试算把每店商品额与总件数按模板规则正确交给运费
     * 计算器（满额免邮恰好达额 → 0；按件 = 单价 × 件数；包邮恒 0）——
     * 三规则边界经规则语义桩端到端钉在编排层。
     */
    @Test
    @DisplayName("运费三规则边界：恰好达额免邮/按件计费/包邮恒0")
    void estimate_threeRules_freightPerShopWithInputs() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        cartRepository.seed(cartWith(
                entry(PRODUCT_A, SKU_A1, 3, SHOP_A, true),
                entry(PRODUCT_B, SKU_B1, 2, SHOP_B, true),
                entry(PRODUCT_C, SKU_C1, 3, SHOP_C, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                thresholdFreight(TEMPLATE_A, 30000L, 800L), skuOf(SKU_A1, 10000L, 10)));
        queryFacade.returns(viewFor(PRODUCT_B, SHOP_B, "店铺乙",
                perItemFreight(TEMPLATE_B, 500L), skuOf(SKU_B1, 10000L, 5)));
        queryFacade.returns(viewFor(PRODUCT_C, SHOP_C, "店铺丙",
                freeFreight(TEMPLATE_C), skuOf(SKU_C1, 2000L, 9)));

        final RuleFreightCalculator ruleCalculator = new RuleFreightCalculator();
        final PlaceOrderUseCase ruleUseCase = buildUseCase(ruleCalculator);

        final EstimateResult result = ruleUseCase.estimate(BUYER_ID,
                new EstimateRequest(List.of(SKU_A1, SKU_B1, SKU_C1)));

        // 输入正确：每店独立调用（模板 ID + 该店商品额 + 该店总件数）
        assertThat(ruleCalculator.calls()).containsExactly(
                new RuleFreightCalculator.Call(TEMPLATE_A, 30000L, 3),
                new RuleFreightCalculator.Call(TEMPLATE_B, 20000L, 2),
                new RuleFreightCalculator.Call(TEMPLATE_C, 6000L, 3));
        // 规则语义：恰好达额 → 0；按件 → 500×2；包邮 → 0
        assertThat(result.groups()).extracting(EstimateResult.EstimateGroup::freightAmount)
                .containsExactly(0L, 1000L, 0L);
        assertThat(result.groups()).extracting(EstimateResult.EstimateGroup::paidAmount)
                .containsExactly(30000L, 21000L, 6000L);
        assertThat(result.totalFreightAmount()).isEqualTo(1000L);
        assertThat(result.totalPaidAmount()).isEqualTo(57000L);
    }

    /* ================= critical path ================= */

    /**
     * critical：跨店任一 SKU 预占失败 → 整单失败（TD-10 all-or-nothing）
     * ——异常透传、已售店预占完成但零落库、零支付单（同事务回滚边界）。
     */
    @Test
    @DisplayName("任一SKU预占失败→整单失败，无部分落库与支付单")
    void placeOrder_anySkuReserveFailure_abortsWholeOrder() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(
                entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true),
                entry(PRODUCT_B, SKU_B1, 1, SHOP_B, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                freeFreight(TEMPLATE_C), skuOf(SKU_A1, 10000L, 10)));
        queryFacade.returns(viewFor(PRODUCT_B, SHOP_B, "店铺乙",
                freeFreight(TEMPLATE_C), skuOf(SKU_B1, 10000L, 5)));
        final BusinessException shortage = new BusinessException(
                "inventory.stock_insufficient", "SKU 3101 库存不足", 409);
        // 子单 ID 由工厂经 ID 生成器产出（无法预知）：按调用序抛异常——
        // 第 2 店（即第二次预占）预占失败，模拟跨店任一 SKU 不足
        final AtomicInteger preoccupyCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (preoccupyCalls.incrementAndGet() == 2) {
                throw shortage;
            }
            return null;
        }).when(inventoryFacade).preoccupy(anyLong(), anyList());

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1, SKU_B1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("inventory.stock_insufficient");
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                });

        // 预占失败后：任何存单（子单/主单）与支付单创建均未发生（回滚编排）
        assertThat(subOrderRepository.savedSubOrders()).isEmpty();
        assertThat(masterOrderRepository.savedMasterOrders()).isEmpty();
        verify(paymentPort, never()).createPendingPayment(anyLong(), anyLong(), anyLong());
    }

    /**
     * critical：满额免邮恰好达阈值 → 运费 0（含恰好达额的边界放行）。
     */
    @Test
    @DisplayName("满额免邮恰好达阈值→免邮")
    void placeOrder_thresholdExactlyReached_freeShipment() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 3, SHOP_A, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                thresholdFreight(TEMPLATE_A, 30000L, 800L), skuOf(SKU_A1, 10000L, 10)));
        useCase = buildUseCase(new RuleFreightCalculator());

        useCase.placeOrder(BUYER_ID, new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1)));

        final SubOrder sub = subOrderRepository.lastSaved();
        assertThat(sub.getAmount().getGoodsAmount()).isEqualTo(30000L);
        assertThat(sub.getAmount().getFreightAmount()).isZero();
        assertThat(sub.getAmount().getPaidAmount()).isEqualTo(30000L);
    }

    /**
     * critical：满额免邮低于阈值 → 收基础运费。
     */
    @Test
    @DisplayName("满额免邮低于阈值→收基础运费")
    void placeOrder_belowThreshold_baseFreightCharged() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 3, SHOP_A, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                thresholdFreight(TEMPLATE_A, 31000L, 800L), skuOf(SKU_A1, 9000L, 10)));
        useCase = buildUseCase(new RuleFreightCalculator());

        useCase.placeOrder(BUYER_ID, new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1)));

        final SubOrder sub = subOrderRepository.lastSaved();
        assertThat(sub.getAmount().getGoodsAmount()).isEqualTo(27000L);
        assertThat(sub.getAmount().getFreightAmount()).isEqualTo(800L);
        assertThat(sub.getAmount().getPaidAmount()).isEqualTo(27800L);
    }

    /**
     * critical：购物车勾选集过滤——勾选 3 条只选 2 条提交，未选条目不进
     * 订单与预占（B7.2② 服务端强制）。
     */
    @Test
    @DisplayName("勾选集过滤：未选条目不进订单")
    void placeOrder_selectionFiltersCheckedCartItems() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(
                entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true),
                entry(PRODUCT_A, SKU_A2, 3, SHOP_A, true),
                entry(PRODUCT_B, SKU_B1, 1, SHOP_B, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                freeFreight(TEMPLATE_C),
                skuOf(SKU_A1, 10000L, 10), skuOf(SKU_A2, 5000L, 20)));
        queryFacade.returns(viewFor(PRODUCT_B, SHOP_B, "店铺乙",
                freeFreight(TEMPLATE_C), skuOf(SKU_B1, 10000L, 5)));

        final OrderResult result = useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1, SKU_B1)));

        // 只落 A1/B1 两个子单（A2 未选不进订单）
        final List<SubOrder> subs = subOrderRepository.savedSubOrders();
        assertThat(subs).hasSize(2);
        assertThat(subs.get(0).getItems()).extracting(OrderItem::getSkuId)
                .containsExactly(SKU_A1);
        assertThat(subs.get(1).getItems()).extracting(OrderItem::getSkuId)
                .containsExactly(SKU_B1);
        verify(inventoryFacade).preoccupy(eq(subs.get(0).getId()),
                eq(List.of(new StockChangeItem(SKU_A1, 2))));
        verify(inventoryFacade).preoccupy(eq(subs.get(1).getId()),
                eq(List.of(new StockChangeItem(SKU_B1, 1))));
        assertThat(result.subOrders()).hasSize(2);
    }

    /* ================= fail path ================= */

    /**
     * fail：下单条目为空（skuIds 空）拒绝（空订单无业务意义）。
     */
    @Test
    @DisplayName("下单条目为空拒绝")
    void placeOrder_emptySelection_rejected() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of())))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("order.place_empty");
                    assertThat(error.getHttpStatus()).isEqualTo(400);
                });
    }

    /**
     * fail：提交的 SKU 不在购物车勾选集中（未勾选/已移除）拒绝——回购物
     * 车重新选择（B7.2 服务端强制）。
     */
    @Test
    @DisplayName("未勾选条目下单拒绝")
    void placeOrder_skuNotChecked_rejected() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 2, SHOP_A, false)));

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("order.item_not_checked");
                    assertThat(error.getHttpStatus()).isEqualTo(409);
                });
    }

    /**
     * fail：买家账号不存在拒绝（统一 403，防账号存在性泄露）。
     */
    @Test
    @DisplayName("买家不存在拒绝")
    void placeOrder_buyerMissing_rejected() {
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                freeFreight(TEMPLATE_C), skuOf(SKU_A1, 10000L, 10)));

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("order.buyer_not_purchasable");
                    assertThat(error.getHttpStatus()).isEqualTo(403);
                });
    }

    /**
     * fail：买家封禁拒绝（与不存在统一语义）。
     */
    @Test
    @DisplayName("封禁买家下单拒绝")
    void placeOrder_buyerBanned_rejected() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.BANNED));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true)));

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("order.buyer_not_purchasable");
                    assertThat(error.getHttpStatus()).isEqualTo(403);
                });
    }

    /**
     * fail：下单地址不属于当前买家地址簿拒绝（越权使用他人地址）。
     */
    @Test
    @DisplayName("地址不属于买家拒绝")
    void placeOrder_addressNotOwned_rejected() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                freeFreight(TEMPLATE_C), skuOf(SKU_A1, 10000L, 10)));

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(OTHER_ADDRESS_ID, List.of(SKU_A1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("order.address_not_found");
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
    }

    /**
     * fail：商品非在售按不存在拒绝（目录侧 404 透传，不泄露生命周期状态；
     * 下单与试算同语义）。
     */
    @Test
    @DisplayName("非在售商品下单拒绝（404 透传）")
    void placeOrder_productNotOnSale_notFoundPassthrough() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true)));
        // 门面无视图：按不存在呈现

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("catalog.product_not_found");
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
    }

    /**
     * fail：试算命中非在售商品同样 404 透传（试算与下单同源同语义）。
     */
    @Test
    @DisplayName("非在售商品试算拒绝（404 透传）")
    void estimate_productNotOnSale_notFoundPassthrough() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A1, 2, SHOP_A, true)));

        assertThatThrownBy(() -> useCase.estimate(BUYER_ID,
                new EstimateRequest(List.of(SKU_A1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("catalog.product_not_found");
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                });
    }

    /**
     * fail：SKU 不属于请求商品拒绝（请求与商品内容不匹配，400）。
     */
    @Test
    @DisplayName("SKU 不属于商品拒绝")
    void placeOrder_skuNotInProduct_rejected() {
        accountRepository.seed(account(BUYER_ID, AccountStatus.NORMAL));
        addressBookRepository.seed(bookWith(address(ADDRESS_ID)));
        cartRepository.seed(cartWith(entry(PRODUCT_A, SKU_A2, 2, SHOP_A, true)));
        queryFacade.returns(viewFor(PRODUCT_A, SHOP_A, "店铺甲",
                freeFreight(TEMPLATE_C), skuOf(SKU_A1, 10000L, 10)));

        assertThatThrownBy(() -> useCase.placeOrder(BUYER_ID,
                new PlaceOrderRequest(ADDRESS_ID, List.of(SKU_A2))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getBusinessCode()).isEqualTo("order.place_sku_invalid");
                    assertThat(error.getHttpStatus()).isEqualTo(400);
                });
    }

    /* ================= fixtures ================= */

    private static Account account(long id, AccountStatus status) {
        return new Account(id, "buyer" + id, "hash", status, AccountType.BUYER);
    }

    private static Address address(long id) {
        return new Address(id, BOOK_ID, "张三", "13800138000", "浙江省", "杭州市", "西湖区",
                "文一西路100号", true);
    }

    private static AddressBook bookWith(Address... addresses) {
        final AddressBook book = new AddressBook(BOOK_ID, BUYER_ID);
        for (final Address address : addresses) {
            book.add(address);
        }
        return book;
    }

    private static Cart cartWith(CartItem... items) {
        return new Cart(CART_ID, BUYER_ID, List.of(items));
    }

    private static CartItem entry(Long productId, Long skuId, int qty, Long shopId, boolean checked) {
        return new CartItem(skuId, CART_ID, BUYER_ID, productId, skuId, qty, checked, shopId,
                shopId == SHOP_A ? "店铺甲" : shopId == SHOP_B ? "店铺乙" : "店铺丙");
    }

    private static ProductBuyerView viewFor(Long productId, Long shopId, String shopName,
                                            ProductBuyerView.Freight freight,
                                            ProductBuyerView.Sku... skus) {
        return new ProductBuyerView(productId, shopId, "测试商品" + productId, "测试描述",
                List.of(new ProductBuyerView.Image("/files/img-" + productId, true)),
                List.of(), List.of(), List.of(skus), freight,
                new ProductBuyerView.Shop(shopId, shopName, null));
    }

    private static ProductBuyerView.Sku skuOf(Long skuId, long price, int available) {
        return new ProductBuyerView.Sku(skuId, "h" + skuId, "规格", price, available);
    }

    private static ProductBuyerView.Freight thresholdFreight(Long templateId, long threshold, long base) {
        return new ProductBuyerView.Freight(templateId, "满额免邮", "THRESHOLD_FREE", null, base, threshold);
    }

    private static ProductBuyerView.Freight perItemFreight(Long templateId, long perItemPrice) {
        return new ProductBuyerView.Freight(templateId, "按件", "PER_ITEM", perItemPrice, null, null);
    }

    private static ProductBuyerView.Freight freeFreight(Long templateId) {
        return new ProductBuyerView.Freight(templateId, "包邮", "FREE", null, null, null);
    }

    /* ================= stubs ================= */

    /**
     * 账号仓储内存桩（按 ID 索引；未 seed = 不存在）。买家状态校验的
     * 数据源（存在 + NORMAL 方可下单）。
     */
    private static final class StubAccountRepository implements AccountRepository {

        private final Map<Long, Account> byId = new HashMap<>();

        void seed(Account account) {
            byId.put(account.getId(), account);
        }

        @Override
        public Account getByID(Long id) {
            return byId.get(id);
        }

        @Override
        public Optional<Account> findByTypeAndUsername(AccountType type, String username) {
            return byId.values().stream()
                    .filter(account -> account.getType() == type && account.getUsername().equals(username))
                    .findFirst();
        }

        @Override
        public boolean save(Account domain) {
            return false;
        }

        @Override
        public int delete(Account domain) {
            return 0;
        }

        @Override
        public int deleteByID(Long id) {
            return 0;
        }
    }

    /**
     * 地址簿仓储内存桩：按买家索引；无簿时创建空簿（聚合恒存在）。
     */
    private static final class StubAddressBookRepository implements AddressBookRepository {

        private final Map<Long, AddressBook> booksByAccount = new HashMap<>();

        void seed(AddressBook book) {
            booksByAccount.put(book.getAccountId(), book);
        }

        @Override
        public AddressBook getByAccountId(Long accountId) {
            return booksByAccount.computeIfAbsent(accountId,
                    ignored -> new AddressBook(BOOK_ID, accountId));
        }

        @Override
        public AddressBook getByID(Long id) {
            return booksByAccount.values().stream()
                    .filter(book -> book.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public boolean save(AddressBook book) {
            return true;
        }

        @Override
        public void lockBuyer(Long accountId) {
        }

        @Override
        public int delete(AddressBook domain) {
            return 0;
        }

        @Override
        public int deleteByID(Long id) {
            return 0;
        }
    }

    /**
     * 购物车仓储内存桩：按买家索引缓存购物车，无车时创建空车；保存
     * 记录最近落库对象。
     */
    private static final class StubCartRepository implements CartRepository {

        private final CartFactory cartFactory;
        private final Map<Long, Cart> cartsByBuyer = new HashMap<>();
        private Cart lastSaved;

        StubCartRepository(CartFactory cartFactory) {
            this.cartFactory = cartFactory;
        }

        void seed(Cart cart) {
            cartsByBuyer.put(cart.getBuyerId(), cart);
        }

        Cart lastSaved() {
            return lastSaved;
        }

        @Override
        public Cart getByBuyerId(Long buyerId) {
            return cartsByBuyer.computeIfAbsent(buyerId, cartFactory::createCart);
        }

        @Override
        public boolean save(Cart cart) {
            lastSaved = cart;
            cartsByBuyer.put(cart.getBuyerId(), cart);
            return true;
        }

        @Override
        public void lockBuyer(Long buyerId) {
        }

        @Override
        public Cart getByID(Long id) {
            return cartsByBuyer.values().stream()
                    .filter(cart -> cart.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int delete(Cart cart) {
            return cart == null ? 0 : deleteByID(cart.getId());
        }

        @Override
        public int deleteByID(Long id) {
            final int before = cartsByBuyer.size();
            cartsByBuyer.values().removeIf(cart -> cart.getId().equals(id));
            return before - cartsByBuyer.size();
        }
    }

    /**
     * 主订单仓储内存桩：记录每次落库（编排断言：金额恒等式/顺序/次数）。
     */
    private static final class StubMasterOrderRepository implements MasterOrderRepository {

        private final List<MasterOrder> saved = new ArrayList<>();

        List<MasterOrder> savedMasterOrders() {
            return List.copyOf(saved);
        }

        MasterOrder lastSaved() {
            return saved.isEmpty() ? null : saved.get(saved.size() - 1);
        }

        @Override
        public MasterOrder getByID(Long id) {
            return null;
        }

        @Override
        public boolean save(MasterOrder domain) {
            saved.add(domain);
            return true;
        }

        @Override
        public int delete(MasterOrder domain) {
            return 0;
        }

        @Override
        public int deleteByID(Long id) {
            return 0;
        }
    }

    /**
     * 子订单仓储内存桩：记录每次落库（拆单断言：店铺/金额/条目快照）。
     */
    private static final class StubSubOrderRepository implements SubOrderRepository {

        private final List<SubOrder> saved = new ArrayList<>();

        List<SubOrder> savedSubOrders() {
            return List.copyOf(saved);
        }

        SubOrder lastSaved() {
            return saved.isEmpty() ? null : saved.get(saved.size() - 1);
        }

        @Override
        public List<SubOrder> getByMasterOrderId(Long masterOrderId) {
            return saved.stream().filter(sub -> sub.getMasterOrderId().equals(masterOrderId)).toList();
        }

        @Override
        public List<SubOrder> findDueByStatusAndTimeoutAtBefore(SubOrderStatus status,
                                                                Instant now, int limit) {
            throw new UnsupportedOperationException("红阶段占位：超时扫描面非本桩服务范围（归属仓储接线 WU）");
        }

        @Override
        public boolean claimTimeout(Long id, SubOrderStatus expectedStatus) {
            throw new UnsupportedOperationException("红阶段占位：超时认领非本桩服务范围（归属仓储接线 WU）");
        }

        @Override
        public void clearTimeoutDeadline(Long id) {
            throw new UnsupportedOperationException("红阶段占位：超时清除非本桩服务范围（归属仓储接线 WU）");
        }

        @Override
        public SubOrder getByID(Long id) {
            return null;
        }

        @Override
        public boolean save(SubOrder domain) {
            saved.add(domain);
            return true;
        }

        @Override
        public int delete(SubOrder domain) {
            return 0;
        }

        @Override
        public int deleteByID(Long id) {
            return 0;
        }
    }

    /**
     * 商品查询门面桩：按商品索引；缺行按不存在呈现（404 在售校验语义；
     * 全局失败可编程，优先级最高）。
     */
    private static final class StubProductQueryFacade implements ProductQueryFacade {

        private final Map<Long, ProductBuyerView> views = new HashMap<>();
        private BusinessException failure;

        void returns(ProductBuyerView view) {
            views.put(view.productId(), view);
            failure = null;
        }

        @Override
        public ProductBuyerView getBuyerView(Long productId) {
            if (failure != null) {
                throw failure;
            }
            final ProductBuyerView view = views.get(productId);
            if (view == null) {
                throw new BusinessException("catalog.product_not_found", "商品不存在或已下架", 404);
            }
            return view;
        }
    }

    /**
     * 运费计算器桩（可编程）：按模板 ID 返回预设运费，并记录调用参数
     * （编排断言用：输入 = 该店商品额 + 总件数）。
     */
    private static class StubFreightCalculator implements FreightCalculator {

        private final Map<Long, Long> freightByTemplateId = new HashMap<>();
        private final List<Call> calls = new ArrayList<>();

        void setFreight(Long templateId, long freight) {
            freightByTemplateId.put(templateId, freight);
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        @Override
        public long calculate(FreightTemplate template, long itemAmount, int itemCount) {
            calls.add(new Call(template.getId(), itemAmount, itemCount));
            return freightByTemplateId.getOrDefault(template.getId(), 0L);
        }

        record Call(Long templateId, long itemAmount, int itemCount) {
        }
    }

    /**
     * 运费计算器桩（规则语义版）：按模板三规则真实计算——满额免邮（含
     * 恰好达额）、按件单价 × 件数、包邮恒 0（模拟目录域计算器行为，
     * 用于钉编排层三规则边界）。
     */
    private static final class RuleFreightCalculator implements FreightCalculator {

        private final List<Call> calls = new ArrayList<>();

        List<Call> calls() {
            return List.copyOf(calls);
        }

        @Override
        public long calculate(FreightTemplate template, long itemAmount, int itemCount) {
            calls.add(new Call(template.getId(), itemAmount, itemCount));
            return switch (template.getRuleType()) {
                case FREE -> 0L;
                case PER_ITEM -> template.getPerItemPrice() * itemCount;
                case THRESHOLD_FREE ->
                        itemAmount >= template.getFreeThreshold() ? 0L : template.getBaseFreight();
            };
        }

        record Call(Long templateId, long itemAmount, int itemCount) {
        }
    }
}