package com.nona.application.mall;

import com.nona.api.mall.EstimateRequest;
import com.nona.api.mall.EstimateResult;
import com.nona.api.mall.OrderResult;
import com.nona.api.mall.PlaceOrderRequest;
import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.domain.catalog.ports.FreightCalculator;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.domain.identity.entity.Account;
import com.nona.domain.identity.entity.AccountStatus;
import com.nona.domain.identity.entity.Address;
import com.nona.domain.identity.entity.AddressBook;
import com.nona.domain.identity.repo.AccountRepository;
import com.nona.domain.identity.repo.AddressBookRepository;
import com.nona.domain.inventory.ports.InventoryFacade;
import com.nona.domain.inventory.ports.StockChangeItem;
import com.nona.domain.order.entity.AddressSnapshot;
import com.nona.domain.order.entity.AmountDetail;
import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.domain.order.entity.MasterOrder;
import com.nona.domain.order.entity.OrderItem;
import com.nona.domain.order.entity.SubOrder;
import com.nona.domain.order.factory.MasterOrderFactory;
import com.nona.domain.order.factory.SubOrderFactory;
import com.nona.domain.order.repo.CartRepository;
import com.nona.domain.order.repo.MasterOrderRepository;
import com.nona.domain.order.repo.SubOrderRepository;
import com.nona.domain.payment.ports.PaymentPort;
import com.nona.domain.payment.ports.PendingPayment;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.timeout.TimeoutType;
import com.nona.util.IDUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 下单编排用例（买家端）：结算试算 + 提交订单（跨上下文同事务，应用层
 * 用例协议 + TD-10 拆单）。
 * <p>
 * <b>提交订单编排序</b>：
 * <ol>
 *     <li><b>前置读校验</b>（纯读，方法级 {@link CrossTenant} 放行读
 *         tenant 表）：买家状态—账号存在且正常，否则统一
 *         {@code order.buyer_not_purchasable}（403，防账号存在性泄露）；
 *         地址—addressId 必须属于当前买家地址簿，否则
 *         {@code order.address_not_found}（404）；</li>
 *     <li><b>购物车勾选集消费</b>（B7.2 服务端权威）：请求 skuIds 为空
 *         → {@code order.place_empty}（400）；逐 SKU 必须在勾选集中
 *         （含未勾选/已移除），否则 {@code order.item_not_checked}（409，
 *         回购物车重新选择）——判定顺序：空请求先拒绝（400），再逐条
 *         归属判定（409），勾选集为空且请求非空时逐条落到 409（与
 *         测试判例一致，不设「求交为空」独立分支）；数量/商品/店铺
 *         分组锚点全部取自勾选条目；立即购买（B7.1）由前端先行加购
 *         （默认勾选）进入同一路径；</li>
 *     <li><b>商品前置读</b>：逐条目 {@link ProductQueryFacade#getBuyerView}
 *         （非在售统一 404 透传，不泄露生命周期）→ SKU 归属断言
 *         （{@code order.place_sku_invalid}，400）→ 快照装配
 *         （名称/单价/数量/小计/主图/规格摘要，B7.6② 固化）；</li>
 *     <li><b>试算</b>（O2/B7.4）：按店铺分组（锚点 = 商品视图 shopId，
 *         服务端权威）→ 每店以商品额/总件数调用
 *         {@link FreightCalculator#calculate}（三规则；模板概要自买家
 *         视图装配——主会话裁定：视图 freight 恒非空，消费面无 null
 *         分支）→ 每店 {@code AmountDetail} 装配（商品额 = Σ 条目
 *         小计；实付 = 商品额 + 运费，优惠位恒 0）→ 主单四维 = Σ 子单
 *         （金额恒等式由聚合构造校验，order.amount_mismatch 兜底）；</li>
 *     <li><b>跨租户写段</b>（TD-12：买家家视角写店数据必须提权）：
 *         {@link TenantPrivilege#elevatedInTransaction} 内——逐店
 *         {@link SubOrderFactory} 创建子单（内存，拿子单 ID 与金额投
 *         影）→ 逐店 {@link InventoryFacade#preoccupy}(subOrderId,
 *         items)（CAS + (order_id, sku, type) 幂等；<b>任一 SKU 预占
 *         失败 → 整单失败</b>，TD-10 同事务回滚，报错指明不足项）→ 全
 *         部成功后才落库：保存子单（tenant=shopId，PO 显式
 *         setTenantID(shopId)）→ {@link MasterOrderFactory} 创建主单
 *         （global）→ 保存主单；</li>
 *     <li><b>支付单创建 + 超时注册</b>：{@link PaymentPort#createPendingPayment}
 *         （主单 ID，实付金额，支付超时时长 = {@link TimeoutType#ORDER_PAY}
 *         规则值——订单域 D1-o1 30 分钟，契约本阶段冻结、实现接线归
 *         支付域落位）→ 待支付支付单 + deadline（B8.3）；</li>
 *     <li><b>返回订单视图</b>：主单号/子单列表（含店铺名，B7.5③）/
 *         支付单（payNo/金额/截止时间）。</li>
 * </ol>
 * 事务边界 = 用例方法（方法级 {@link Transactional} 统辖读段与写段，
 * 预占失败抛 {@link com.nona.exceptions.BusinessException} → 整体回滚）；
 * 领域方法不做事务。结算试算（{@link #estimate}）为纯读展示（与提交
 * 同源消费勾选集 + 同一校验（买家/条目/在售/归属），不落库、不开写
 * 事务；买家由认证上下文传入——与 placeOrder 对称，web 层解析身份）。
 * <p>
 * 租户纪律：读放行（{@code @CrossTenant}）与写放行（elevatedInTransaction
 * + 显式 setTenantID）只出现在本类（application 层），domain 内不放行。
 * <p>
 * 业务单号（TD-13：前缀 + 日期 + snowflake 后段）：主单
 * {@code ORD+yyyyMMdd+id}、子单 {@code SO+yyyyMMdd+id}（子单号前缀
 * SO 与主单 ORD/支付单 PAY 同构，唯一性由 snowflake 保证；生成逻辑
 * 收敛在本类）。
 * <p>
 * 装配声明：用例类<b>不注册为容器 bean</b>——跨上下文写依赖
 * （MasterOrder/SubOrder 仓储实现）与支付端口（PaymentPort 实现）未
 * 接线，注册会导致全量集成测试 context 启动失败（红阶段装配学习）；
 * 以构造器注入声明装配契约，Spring 注册（{@code @Service}）随接线
 * WU 落位恢复；单测以构造器直接装配（见用例测试）。
 *
 * @author nona9961
 */
@Service
public class PlaceOrderUseCase {

    /**
     * 账号仓储（买家状态前置校验：存在 + 正常）
     */
    private final AccountRepository accountRepository;

    /**
     * 地址簿仓储（下单地址归属校验 + 快照来源）
     */
    private final AddressBookRepository addressBookRepository;

    /**
     * 购物车仓储（勾选集消费：条目/数量/店铺分组锚点）
     */
    private final CartRepository cartRepository;

    /**
     * 商品查询门面（在售校验 + SKU 归属 + 快照内容 + 运费模板概要 + 店铺卡片）
     */
    private final ProductQueryFacade productQueryFacade;

    /**
     * 运费计算器（三规则：包邮/按件/满额免邮——每店按模板计费）
     */
    private final FreightCalculator freightCalculator;

    /**
     * 主订单工厂（主单创建入口，金额恒等式复核）
     */
    private final MasterOrderFactory masterOrderFactory;

    /**
     * 子订单工厂（子单创建入口，装配形态校验）
     */
    private final SubOrderFactory subOrderFactory;

    /**
     * 主订单仓储（主单落库，global 表）
     */
    private final MasterOrderRepository masterOrderRepository;

    /**
     * 子订单仓储（子单落库，tenant=shopId 表）
     */
    private final SubOrderRepository subOrderRepository;

    /**
     * 库存门面（下单预占：CAS + 幂等；任一失败整单回滚）
     */
    private final InventoryFacade inventoryFacade;

    /**
     * 支付端口（待支付支付单创建 + 支付超时 deadline 注册）
     */
    private final PaymentPort paymentPort;

    /**
     * 提权工具（跨租户写段 elevatedInTransaction 放行）
     */
    private final TenantPrivilege tenantPrivilege;

    /**
     * 事务模板（提权事务编排）
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 业务单号日期段格式（TD-13：前缀 + 日期 + snowflake 后段）。
     */
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 构造下单编排用例。
     *
     * @param accountRepository      账号仓储
     * @param addressBookRepository  地址簿仓储
     * @param cartRepository         购物车仓储
     * @param productQueryFacade     商品查询门面
     * @param freightCalculator      运费计算器
     * @param masterOrderFactory     主订单工厂
     * @param subOrderFactory        子订单工厂
     * @param masterOrderRepository  主订单仓储
     * @param subOrderRepository     子订单仓储
     * @param inventoryFacade        库存门面
     * @param paymentPort            支付端口
     * @param tenantPrivilege        提权工具
     * @param transactionTemplate    事务模板
     */
    public PlaceOrderUseCase(AccountRepository accountRepository,
                             AddressBookRepository addressBookRepository,
                             CartRepository cartRepository,
                             ProductQueryFacade productQueryFacade,
                             FreightCalculator freightCalculator,
                             MasterOrderFactory masterOrderFactory,
                             SubOrderFactory subOrderFactory,
                             MasterOrderRepository masterOrderRepository,
                             SubOrderRepository subOrderRepository,
                             InventoryFacade inventoryFacade,
                             PaymentPort paymentPort,
                             TenantPrivilege tenantPrivilege,
                             TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.addressBookRepository = addressBookRepository;
        this.cartRepository = cartRepository;
        this.productQueryFacade = productQueryFacade;
        this.freightCalculator = freightCalculator;
        this.masterOrderFactory = masterOrderFactory;
        this.subOrderFactory = subOrderFactory;
        this.masterOrderRepository = masterOrderRepository;
        this.subOrderRepository = subOrderRepository;
        this.inventoryFacade = inventoryFacade;
        this.paymentPort = paymentPort;
        this.tenantPrivilege = tenantPrivilege;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 提交订单（编排序见类注；任一 SKU 预占失败 → 整单失败同事务回滚）。
     *
     * @param buyerId 当前买家账号 ID（认证上下文，不来自请求体）
     * @param request 下单请求（地址 + 勾选条目 SKU 集合）
     * @return 订单视图（主单号/子单列表/待支付支付单）
     */
    @CrossTenant
    @Transactional
    public OrderResult placeOrder(Long buyerId, PlaceOrderRequest request) {
        // 1. 前置读校验（买家 → 地址）
        assertBuyerPurchasable(buyerId);
        final Address address = resolveAddress(buyerId, request.addressId());
        // 2. 购物车勾选集消费（空 400 / 逐条归属 409，判定顺序见类注）
        final List<CartItem> checkedItems = resolveCheckedItems(buyerId, request.skuIds());
        // 3-4. 商品前置读 + 试算装配（按店铺分组，含运费计算与金额明细）
        final Map<Long, ShopGroup> groups = assembleGroups(checkedItems);
        final AmountDetail totalAmount = sumAmount(groups.values());
        // 5-6. 提权写段：逐店子单（内存）→ 逐店预占 → 全部成功才落库
        //       （子单 → 主单）→ 支付单创建 + 超时注册
        final Placed placed;
        try {
            placed = tenantPrivilege.elevatedInTransaction(transactionTemplate, () -> {
                final Long masterOrderId = IDUtils.generateID();
                final AddressSnapshot addressSnapshot = toSnapshot(address);
                final List<SubOrder> subOrders = new ArrayList<>(groups.size());
                for (final ShopGroup group : groups.values()) {
                    final SubOrder subOrder = subOrderFactory.createSubOrder(masterOrderId,
                            group.shopId, generateSubOrderNo(), addressSnapshot, group.amount,
                            group.orderItems);
                    subOrders.add(subOrder);
                    inventoryFacade.preoccupy(subOrder.getId(), group.stockItems);
                }
                for (final SubOrder subOrder : subOrders) {
                    subOrderRepository.save(subOrder);
                }
                final List<Long> subOrderIds =
                        subOrders.stream().map(SubOrder::getId).toList();
                final List<AmountDetail> subAmounts =
                        subOrders.stream().map(SubOrder::getAmount).toList();
                final MasterOrder masterOrder = masterOrderFactory.createMasterOrder(
                        generateOrderNo(), buyerId, addressSnapshot, totalAmount,
                        subOrderIds, subAmounts);
                masterOrderRepository.save(masterOrder);
                final PendingPayment payment = paymentPort.createPendingPayment(
                        masterOrder.getId(), masterOrder.getAmount().getPaidAmount(),
                        TimeoutType.ORDER_PAY.durationMillis());
                return new Placed(masterOrder, subOrders, payment);
            });
        } catch (RuntimeException e) {
            // 业务异常（含预占失败）原样透传 → 方法级 @Transactional 整体回滚
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("下单提权事务失败", e);
        }
        // 7. 返回订单视图（主单号/子单列表含店铺名/支付单）
        return toOrderResult(placed.masterOrder(), placed.subOrders(), placed.payment(),
                groups);
    }

    /**
     * 结算试算（O2/B7.4 运费与金额明细展示；与下单同源消费勾选集 + 同一
     * 校验（买家/条目/在售/归属），纯读不落库、不开写事务）。
     *
     * @param buyerId 当前买家账号 ID（认证上下文，与 placeOrder 对称——
     *                红阶段签名缺陷裁定后补入，web 层解析身份传入）
     * @param request 试算请求（购物车勾选条目 SKU 集合）
     * @return 按店铺分组的金额明细（含运费与合计）
     */
    @CrossTenant
    public EstimateResult estimate(Long buyerId, EstimateRequest request) {
        assertBuyerPurchasable(buyerId);
        final List<CartItem> checkedItems = resolveCheckedItems(buyerId, request.skuIds());
        final Map<Long, ShopGroup> groups = assembleGroups(checkedItems);
        final List<EstimateResult.EstimateGroup> rows = groups.values().stream().map(group -> {
            final long paid = group.amount.getPaidAmount();
            return new EstimateResult.EstimateGroup(group.shopId, group.shopName,
                    group.amount.getGoodsAmount(), group.amount.getFreightAmount(),
                    group.amount.getDiscount(), paid,
                    group.orderItems.stream()
                            .map(item -> new EstimateResult.EstimateItem(
                                    item.getProductId(), item.getSkuId(),
                                    item.getProductName(), item.getUnitPrice(),
                                    item.getQuantity(), item.getSubtotal()))
                            .toList());
        }).toList();
        final long totalGoods = rows.stream().mapToLong(EstimateResult.EstimateGroup::goodsAmount).sum();
        final long totalFreight = rows.stream().mapToLong(EstimateResult.EstimateGroup::freightAmount).sum();
        final long totalDiscount = rows.stream().mapToLong(EstimateResult.EstimateGroup::discount).sum();
        final long totalPaid = rows.stream().mapToLong(EstimateResult.EstimateGroup::paidAmount).sum();
        return new EstimateResult(rows, totalGoods, totalFreight, totalDiscount, totalPaid);
    }

    /**
     * 买家状态前置校验：账号存在且 NORMAL 方可下单/试算——不存在与封禁
     * 统一 {@code order.buyer_not_purchasable}（403，防账号存在性泄露）。
     *
     * @param buyerId 买家账号 ID
     */
    private void assertBuyerPurchasable(Long buyerId) {
        final Account account = accountRepository.getByID(buyerId);
        if (account == null || account.getStatus() != AccountStatus.NORMAL) {
            throw new BusinessException(
                    EcommerceBusinessCode.ORDER_BUYER_NOT_PURCHASABLE.code(),
                    "买家不存在或不可购买", 403);
        }
    }

    /**
     * 下单地址校验与解析：addressId 必须属于当前买家地址簿（越权使用
     * 他人地址按不存在拒绝，不泄露归属）。
     *
     * @param buyerId   买家账号 ID
     * @param addressId 请求地址 ID
     * @return 归属当前买家的地址
     */
    private Address resolveAddress(Long buyerId, Long addressId) {
        final AddressBook book = addressBookRepository.getByAccountId(buyerId);
        return book.getById(addressId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.ORDER_ADDRESS_NOT_FOUND.code(),
                        "收货地址不存在", 404));
    }

    /**
     * 购物车勾选集消费（B7.2 服务端权威）：请求 skuIds 为空 → 400
     * {@code order.place_empty}；逐 SKU 必须在购物车勾选集中（未勾选/
     * 已移除均拒绝）→ 409 {@code order.item_not_checked}（判定顺序：
     * 空请求先拒、再逐条判定，勾选集为空且请求非空逐条落 409——按测试
     * 判例实现，不设「求交为空」独立分支）。
     *
     * @param buyerId 买家账号 ID
     * @param skuIds  请求勾选条目 SKU 集合
     * @return 命中请求的勾选条目列表（保持购物车加购序）
     */
    private List<CartItem> resolveCheckedItems(Long buyerId, List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_PLACE_EMPTY.code(),
                    "请选择要结算的商品", 400);
        }
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        final List<CartItem> checked = cart.listChecked();
        final Set<Long> requested = new HashSet<>(skuIds);
        for (final Long skuId : requested) {
            final boolean inChecked = checked.stream()
                    .anyMatch(item -> item.getSkuId().equals(skuId));
            if (!inChecked) {
                throw new BusinessException(
                        EcommerceBusinessCode.ORDER_ITEM_NOT_CHECKED.code(),
                        "商品未勾选或不在购物车中，请重新选择：SKU " + skuId, 409);
            }
        }
        return checked.stream().filter(item -> requested.contains(item.getSkuId())).toList();
    }

    /**
     * 商品前置读 + 试算装配：逐勾选条目读买家商品视图（非在售 404 透传）
     * → SKU 归属断言（400）→ 快照装配（B7.6②）→ 按商品视图 shopId 分组
     * （服务端权威，决策表 #6）→ 每店按模板三规则计算运费（模板概要自
     * 视图装配；恒非空契约，无 null 分支）→ 每店金额明细。
     *
     * @param checkedItems 勾选条目（购物车加购序）
     * @return 店铺分组映射（保序：组序与组内条目序 = 加购序）
     */
    private Map<Long, ShopGroup> assembleGroups(List<CartItem> checkedItems) {
        final Map<Long, ShopGroup> groups = new LinkedHashMap<>();
        for (final CartItem item : checkedItems) {
            final ProductBuyerView view = productQueryFacade.getBuyerView(item.getProductId());
            final ProductBuyerView.Sku sku = requireSku(view, item.getSkuId());
            final FreightTemplate template = toTemplate(view.freight(), view.shopId());
            final ShopGroup group = groups.computeIfAbsent(view.shopId(),
                    shopId -> new ShopGroup(shopId, view.shop().name(), template));
            final long subtotal = sku.price() * item.getQuantity();
            final OrderItem orderItem = new OrderItem(item.getProductId(), item.getSkuId(),
                    view.name(), sku.price(), item.getQuantity(), subtotal,
                    primaryImageUrl(view), sku.specSummary(), Map.of(), Map.of());
            group.add(orderItem, new StockChangeItem(item.getSkuId(), item.getQuantity()),
                    subtotal);
        }
        for (final ShopGroup group : groups.values()) {
            final long freight = freightCalculator.calculate(group.template,
                    group.goodsAmount, group.itemCount);
            group.amount = new AmountDetail(group.goodsAmount, freight, 0L,
                    group.goodsAmount + freight);
        }
        return groups;
    }

    /**
     * SKU 归属断言：SKU 必须属于请求商品（请求与商品内容不匹配，400
     * {@code order.place_sku_invalid}）；视图价格齐备（在售商品定价
     * 不变量），null 价格同样按无效规格拒绝（防御形态）。
     *
     * @param view  买家商品视图
     * @param skuId SKU ID
     * @return 命中的 SKU 行
     */
    private static ProductBuyerView.Sku requireSku(ProductBuyerView view, Long skuId) {
        final ProductBuyerView.Sku sku = view.skus().stream()
                .filter(candidate -> candidate.skuId().equals(skuId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.ORDER_PLACE_SKU_INVALID.code(),
                        "SKU 不属于该商品：SKU " + skuId + " 不在商品 " + view.productId() + " 下", 400));
        if (sku.price() == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.ORDER_PLACE_SKU_INVALID.code(),
                    "商品未定价：SKU " + skuId, 400);
        }
        return sku;
    }

    /**
     * 运费模板参数装配：买家视图运费概要（恒非空契约——WU-51 未绑定
     * 回退店铺默认模板，本消费面无 null 分支）→ 模板实体（规则枚举
     * 与计费参数校验收敛在模板构造）。
     *
     * @param freight 视图运费模板概要
     * @param shopId  归属店铺 ID（tenant=shopId）
     * @return 模板实体（启用态——在售商品绑定/回退模板恒启用）
     */
    private static FreightTemplate toTemplate(ProductBuyerView.Freight freight, Long shopId) {
        final FreightRuleType ruleType = FreightRuleType.valueOf(freight.ruleType());
        return new FreightTemplate(freight.templateId(), shopId, freight.name(), ruleType,
                freight.perItemPrice(), freight.baseFreight(), freight.freeThreshold(),
                FreightTemplateStatus.ENABLED);
    }

    /**
     * 主图 URL 提取：视图图片集中主图标记的第一张（无主图返回 null——
     * 快照可空形态）。
     *
     * @param view 买家商品视图
     * @return 主图 URL；无主图返回 null
     */
    private static String primaryImageUrl(ProductBuyerView view) {
        return view.images().stream()
                .filter(ProductBuyerView.Image::primary)
                .map(ProductBuyerView.Image::url)
                .findFirst()
                .orElse(null);
    }

    /**
     * 主单金额摘要装配：四维 = Σ 子单（金额恒等式由主单构造校验兜底）。
     *
     * @param groups 店铺分组（各组金额明细已装配）
     * @return 主单金额摘要
     */
    private static AmountDetail sumAmount(Collection<ShopGroup> groups) {
        final long goods = groups.stream().mapToLong(group -> group.amount.getGoodsAmount()).sum();
        final long freight = groups.stream().mapToLong(group -> group.amount.getFreightAmount()).sum();
        final long discount = groups.stream().mapToLong(group -> group.amount.getDiscount()).sum();
        final long paid = groups.stream().mapToLong(group -> group.amount.getPaidAmount()).sum();
        return new AmountDetail(goods, freight, discount, paid);
    }

    /**
     * 地址详情 → 订单地址快照（下单时固化，B7.6）。
     *
     * @param address 归属当前买家的地址
     * @return 地址快照
     */
    private static AddressSnapshot toSnapshot(Address address) {
        return new AddressSnapshot(address.getRecipient(), address.getPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(),
                address.getDetail());
    }

    /**
     * 主订单号（TD-13：ORD + 日期 + snowflake 后段，order_no 唯一）。
     *
     * @return 订单号
     */
    private String generateOrderNo() {
        return "ORD" + LocalDate.now().format(ORDER_DATE) + IDUtils.generateID();
    }

    /**
     * 子订单号（与主单 ORD/支付单 PAY 同构：SO + 日期 + snowflake 后段，
     * sub_order_no 唯一；前缀语义 = Sub Order）。
     *
     * @return 子订单号
     */
    private String generateSubOrderNo() {
        return "SO" + LocalDate.now().format(ORDER_DATE) + IDUtils.generateID();
    }

    /**
     * 订单提交结果视图装配：主单号/子单列表（含店铺名 B7.5③）/支付单
     * （payNo/金额/截止时间）。
     *
     * @param masterOrder 主订单（已落库）
     * @param subOrders   子订单列表（创建序）
     * @param payment     待支付支付单
     * @param groups      店铺分组（店铺名来源）
     * @return 订单视图
     */
    private static OrderResult toOrderResult(MasterOrder masterOrder, List<SubOrder> subOrders,
                                             PendingPayment payment, Map<Long, ShopGroup> groups) {
        final List<OrderResult.SubOrderResult> rows = subOrders.stream().map(subOrder -> {
            final ShopGroup group = groups.get(subOrder.getShopId());
            return new OrderResult.SubOrderResult(subOrder.getId(), subOrder.getSubOrderNo(),
                    subOrder.getShopId(), group.shopName,
                    subOrder.getAmount().getGoodsAmount(),
                    subOrder.getAmount().getFreightAmount(),
                    subOrder.getAmount().getPaidAmount());
        }).toList();
        return new OrderResult(masterOrder.getId(), masterOrder.getOrderNo(), rows,
                new OrderResult.PaymentResult(payment.paymentOrderId(), payment.payNo(),
                        payment.amount(), payment.timeoutAt()));
    }

    /**
     * 提权写段返回载体（主单 + 子单列表 + 支付单）。
     *
     * @param masterOrder 主订单
     * @param subOrders   子订单列表（创建序）
     * @param payment     待支付支付单
     */
    private record Placed(MasterOrder masterOrder, List<SubOrder> subOrders,
                          PendingPayment payment) {
    }

    /**
     * 试算装配期的店铺分组（用例内私有形态，装配完成后金额明细定型）：
     * 组 = 未来子订单的商品投影——店铺归属（视图 shopId 权威）/运费
     * 模板/订单项快照/预占明细/金额明细。
     */
    private static final class ShopGroup {

        /**
         * 归属店铺 ID（拆单锚点，视图 shopId 服务端权威）
         */
        private final Long shopId;

        /**
         * 店铺名（视图店铺卡片，B7.5③ 展示）
         */
        private final String shopName;

        /**
         * 运费模板（视图概要装配，组内同店共用）
         */
        private final FreightTemplate template;

        /**
         * 订单项快照（加购序）
         */
        private final List<OrderItem> orderItems = new ArrayList<>();

        /**
         * 库存预占明细（SKU + 数量，加购序）
         */
        private final List<StockChangeItem> stockItems = new ArrayList<>();

        /**
         * 商品金额合计（Σ 条目小计）
         */
        private long goodsAmount;

        /**
         * 商品总件数（按件规则计费输入）
         */
        private int itemCount;

        /**
         * 金额明细（试算后定型：goods/freight/discount=0/paid）
         */
        private AmountDetail amount;

        /**
         * 构造店铺分组。
         *
         * @param shopId   归属店铺 ID
         * @param shopName 店铺名
         * @param template 运费模板
         */
        ShopGroup(Long shopId, String shopName, FreightTemplate template) {
            this.shopId = shopId;
            this.shopName = shopName;
            this.template = template;
        }

        /**
         * 追加条目（订单项快照 + 预占明细 + 金额/件数累积）。
         *
         * @param orderItem   订单项快照
         * @param stockItem   库存预占明细
         * @param subtotal    条目小计金额（分，单价 × 数量）
         */
        void add(OrderItem orderItem, StockChangeItem stockItem, long subtotal) {
            orderItems.add(orderItem);
            stockItems.add(stockItem);
            goodsAmount += subtotal;
            itemCount += stockItem.quantity();
        }
    }
}