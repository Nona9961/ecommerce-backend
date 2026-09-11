package com.nona.domain.order.entity;

import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 主订单聚合根（master_order 主表行，买家维度 global）：买家维度的
 * 订单外壳——订单号/买家/地址快照/金额摘要/整体状态/子单引用集合。
 * <p>
 * 持久化形态（契约声明）：master_order 主表（global，独立
 * Snowflake 主键；order_no 唯一；buyer_id 业务关联列；地址六字段 +
 * 金额四字段为冗余快照列）；子单 id 集合不经冗余列承载——以
 * sub_order.master_order_id 业务关联列反查（引用 ID 协作，跨聚合不
 * 持子单对象、不建外键）。变更落库适配 DifferRepository：读 → track
 * 快照 → save → 变更集驱动落库（主表唯一行）。
 * <p>
 * 关键不变量（全部收敛在本聚合内，包外无直接字段变更路径）：
 * <ol>
 *     <li>快照冻结：订单号/买家/地址/金额摘要创建时定型不可变
 *         ——字段全 final，无任何变更路径；</li>
 *     <li>金额恒等式：主单金额摘要（商品额/运费/优惠/实付四维）==
 *         Σ 子单金额（主单金额=Σ子单商品+运费；收敛在创建构造
 *         路径，以子单金额投影校验，不一致无法构造）；</li>
 *     <li>整体状态派生：主单状态 = 子单状态聚合派生（
 *         {@link MasterOrderStatusDeriver} 纯函数）——主单不承载独立
 *         状态机；deriveStatus 以子单状态投影为参（跨聚合只传状态值
 *         投影，不加载子单对象），由编排在事务内推进子单后调用刷新；
 *         非法投影组合（如待支付与已支付并存）防御性拒绝；</li>
 *     <li>子单引用集合创建时定型：下单拆单完毕后集合固定，无增删路径
 *         （跨店 N 店铺 → N 子单）。</li>
 * </ol>
 * 创建必须经由 {@link com.nona.domain.order.factory.MasterOrderFactory}
 * + {@link com.nona.util.IDUtils#generateID()}；装载（仓储重建，子单
 * id 集合按 master_order_id 反查）走全量构造器。
 *
 * @author nona9961
 */
public class MasterOrder {

    /**
     * 主订单主键（Snowflake，master_order 主键，聚合根标识）
     */
    private final Long id;

    /**
     * 订单号（ORD + 日期 + snowflake 后段，order_no 唯一）
     */
    private final String orderNo;

    /**
     * 归属买家账号 ID（业务关联列）
     */
    private final Long buyerId;

    /**
     * 地址快照（不可变 VO，下单时固化）
     */
    private final AddressSnapshot address;

    /**
     * 金额摘要（不可变 VO：主单商品总额/运费/实付 = Σ 子单）
     */
    private final AmountDetail amount;

    /**
     * 子单引用 ID 集合（不可变：下单拆单完毕后定型，跨聚合引用 ID）
     */
    private final List<Long> subOrderIds;

    /**
     * 整体状态（派生效：子单状态投影经 deriveStatus 刷新，唯一可变位）
     */
    private MasterOrderStatus status;

    /**
     * 下单时间（审计时间字段，冻结契约补充：新建路径为 null——
     * 「未落库」语义，落库后由满载构造器/转换器回填
     * master_order.create_time；列表/详情读面永远走装载路径，非 null）
     */
    private final LocalDateTime createdAt;

    /**
     * 创建构造器（仅工厂调用）：整体状态定型为待支付（全部子单待支付
     * 的派生结果）；金额恒等式以子单金额投影校验。
     *
     * @param id            主订单主键
     * @param orderNo       订单号（必填非空）
     * @param buyerId       归属买家账号 ID（必填）
     * @param address       地址快照（必填）
     * @param amount        金额摘要（必填，须与子单金额投影合计自洽）
     * @param subOrderIds   子单引用 ID 集合（必填非空，与投影一一对应）
     * @param subAmounts    子单金额投影列表（创建校验用：与 subOrderIds
     *                      等长，四维合计须等于金额摘要）
     */
    public MasterOrder(Long id, String orderNo, Long buyerId, AddressSnapshot address,
                       AmountDetail amount, List<Long> subOrderIds,
                       List<AmountDetail> subAmounts) {
        this(id, orderNo, buyerId, address, amount, subOrderIds, subAmounts,
                MasterOrderStatus.PENDING_PAYMENT);
    }

    /**
     * 带状态构造器（创建委托链中间层：7 参创建构造 → 本构造 → 满载
     * 9 参构造 {@code createdAt=null}——实体统一不感知落库时间，新建
     * 路径语义「未落库」，回填由转换器装载路径承载）。
     *
     * @param id            主订单主键
     * @param orderNo       订单号
     * @param buyerId       归属买家账号 ID
     * @param address       地址快照
     * @param amount        金额摘要
     * @param subOrderIds   子单引用 ID 集合（持久化反查值）
     * @param subAmounts    子单金额投影（装载校验用：全局恒等式防御，
     *                      可传空列表表示跳过——持久化行不冗余子单金额）
     * @param status        整体状态（持久化值）
     */
    public MasterOrder(Long id, String orderNo, Long buyerId, AddressSnapshot address,
                       AmountDetail amount, List<Long> subOrderIds,
                       List<AmountDetail> subAmounts, MasterOrderStatus status) {
        this(id, orderNo, buyerId, address, amount, subOrderIds, subAmounts, status, null);
    }

    /**
     * 满载构造器（仓储重建/转换器装载调用，冻结构造委托链：8 参构造
     * 委托本构造 {@code createdAt=null}——新建路径语义「未落库」；
     * 转换器装载路径显式回填 {@code master_order.create_time}）。
     * <p>
     * 装载路径守卫形态不变量（必填/非空）防脏数据；金额恒等式为创建期
     * 锁定的硬不变量（库中异常数据由子单侧自洽守卫兜底暴露）；不执行
     * 写路径校验（派生属业务写入，装载不适用）。
     *
     * @param id            主订单主键
     * @param orderNo       订单号
     * @param buyerId       归属买家账号 ID
     * @param address       地址快照
     * @param amount        金额摘要
     * @param subOrderIds   子单引用 ID 集合（持久化反查值）
     * @param subAmounts    子单金额投影（装载校验用：全局恒等式防御，
     *                      可传空列表表示跳过——持久化行不冗余子单金额）
     * @param status        整体状态（持久化值）
     * @param createdAt     下单时间（持久化值；新建路径传 null）
     */
    public MasterOrder(Long id, String orderNo, Long buyerId, AddressSnapshot address,
                       AmountDetail amount, List<Long> subOrderIds,
                       List<AmountDetail> subAmounts, MasterOrderStatus status,
                       LocalDateTime createdAt) {
        BusinessAssert.assertNonNull(id, "主订单主键不能为空");
        BusinessAssert.assertTrue(orderNo != null && !orderNo.isBlank(), "订单号不能为空");
        BusinessAssert.assertNonNull(buyerId, "归属买家账号 ID 不能为空");
        BusinessAssert.assertNonNull(address, "主订单地址快照不能为空");
        BusinessAssert.assertNonNull(amount, "主订单金额摘要不能为空");
        BusinessAssert.assertNonNull(subOrderIds, "子单引用集合不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SUB_EMPTY.code(),
                !subOrderIds.isEmpty(), "主订单必须包含至少一个子订单");
        BusinessAssert.assertNonNull(status, "主订单整体状态不能为空");
        if (subAmounts != null && !subAmounts.isEmpty()) {
            assertAmountMatches(subAmounts, amount);
        }
        this.id = id;
        this.orderNo = orderNo;
        this.buyerId = buyerId;
        this.address = address;
        this.amount = amount;
        this.subOrderIds = List.copyOf(subOrderIds);
        this.status = status;
        this.createdAt = createdAt;
    }

    /**
     * 金额恒等式守卫：子单金额投影四维合计 == 主单金额摘要。
     *
     * @param subAmounts 子单金额投影列表
     * @param summary    主单金额摘要
     */
    private static void assertAmountMatches(List<AmountDetail> subAmounts, AmountDetail summary) {
        final long goods = subAmounts.stream().mapToLong(AmountDetail::getGoodsAmount).sum();
        final long freight = subAmounts.stream().mapToLong(AmountDetail::getFreightAmount).sum();
        final long discount = subAmounts.stream().mapToLong(AmountDetail::getDiscount).sum();
        final long paid = subAmounts.stream().mapToLong(AmountDetail::getPaidAmount).sum();
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_AMOUNT_MISMATCH.code(),
                summary.getGoodsAmount() == goods && summary.getFreightAmount() == freight
                        && summary.getDiscount() == discount && summary.getPaidAmount() == paid,
                "主单金额摘要与子单合计不符：goods {} != {} , freight {} != {} , discount {} != {} , paid {} != {}",
                summary.getGoodsAmount(), goods, summary.getFreightAmount(), freight,
                summary.getDiscount(), discount, summary.getPaidAmount(), paid);
    }

    /**
     * 主订单主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 订单号。
     *
     * @return 订单号
     */
    public String getOrderNo() {
        return orderNo;
    }

    /**
     * 归属买家账号 ID。
     *
     * @return 买家账号 ID
     */
    public Long getBuyerId() {
        return buyerId;
    }

    /**
     * 地址快照。
     *
     * @return 地址快照（不可变）
     */
    public AddressSnapshot getAddress() {
        return address;
    }

    /**
     * 金额摘要。
     *
     * @return 金额摘要（不可变）
     */
    public AmountDetail getAmount() {
        return amount;
    }

    /**
     * 子单引用 ID 集合（不可变视图：读取安全、外部不可改）。
     *
     * @return 子单 ID 列表快照
     */
    public List<Long> getSubOrderIds() {
        return List.copyOf(subOrderIds);
    }

    /**
     * 整体状态。
     *
     * @return 整体状态（派生值）
     */
    public MasterOrderStatus getStatus() {
        return status;
    }

    /**
     * 下单时间（只读，满载构造器装配——新建路径为 null 即「未落库」
     * 语义；读面装载后非 null）。
     *
     * @return 下单时间；新建未落库路径为 null
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 按子单状态投影刷新整体状态（派生逻辑委托
     * {@link MasterOrderStatusDeriver} 纯函数）。
     * <p>
     * 调用语义：编排（支付/发货/完成/退款/超时用例）在本事务内推进
     * 子单状态后，把主单下全部子单的状态投影传入本方法重算——跨聚合
     * 只传状态值投影（子单 id 可从 {@link #getSubOrderIds()} 反查，
     * 由调用方装配），主单不加载子单对象。非法投影组合（如待支付与
     * 已支付并存）防御性拒绝（派生规则见纯函数 javadoc）。
     *
     * @param subOrderStatuses 主单下全部子单的状态投影（非空）
     * @return 刷新后的整体状态
     */
    public MasterOrderStatus deriveStatus(Collection<SubOrderStatus> subOrderStatuses) {
        final List<SubOrderStatus> projection =
                subOrderStatuses == null ? null : new ArrayList<>(subOrderStatuses);
        final MasterOrderStatus derived = MasterOrderStatusDeriver.derive(projection);
        this.status = derived;
        return derived;
    }
}