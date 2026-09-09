package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 子订单聚合根（sub_order 主表行，tenant=shopId）：店铺维度的履约单元——
 * 店铺归属/运费与金额/独立状态机/订单项集合/地址快照/运单引用。
 * <p>
 * 持久化形态（红阶段契约声明）：sub_order 主表（tenant=shopId，独立
 * Snowflake 主键；master_order_id 业务关联列非唯一；sub_order_no 唯一；
 * waybill_id 可空；地址/金额为冗余快照列）+ order_item 从表（以
 * sub_order_id（rootId）关联，快照列 + ext JSON 列，(sub_order, sku)
 * 结构唯一）。变更落库适配 DifferRepository：读 → track 快照 → save →
 * 变更集驱动落库（主表行 + 从表条目），归属店铺（tenant）创建时定型
 * 不可变。
 * <p>
 * 关键不变量（全部收敛在本聚合内，包外无直接字段变更路径）：
 * <ol>
 *     <li>快照冻结（B7.6）：地址/金额/订单项（价格/名称/图片/规格）创建
 *         时固化——全部字段 final 或不可变视图，无任何变更路径；</li>
 *     <li>金额自洽：商品总额 == Σ 订单项小计（恒等式收敛在构造路径，与
 *         {@link AmountDetail} 自身恒等式（实付=商品+运费-优惠）叠加
 *         闭合），金额不自洽的子单无法构造；</li>
 *     <li>状态迁移守卫：全部迁移收敛在聚合方法（markPaid/markShipped/
 *         markCompleted/cancel/markRefunding/markRefunded/
 *         closeByTimeout），非法迁移抛 BusinessException
 *         （order.sub_status_illegal）；迁移单向不可逆（待支付可取消除
 *         外），cancelled/refunded/closed 为终态；</li>
 *     <li>发货归属校验：子单只能被其所属店铺（shopId）商家发货——
 *         markShipped 以操作者店铺为参，归属不符拒绝
 *         （order.sub_shop_mismatch；fail-closed 的租户过滤先行，
 *         本校验为提权/装配路径第二道防线）；</li>
 *     <li>运单引用：waybill_id 发货时定型（markShipped 必填），关单/
 *         完成/退款不改变（一子单一在途运单的语义由物流域 WU 承载）。</li>
 * </ol>
 * 创建必须经由 {@link com.nona.domain.order.factory.SubOrderFactory}
 * + {@link com.nona.util.IDUtils#generateID()}；装载（仓储重建）走
 * 全量构造器（不执行写路径校验）。
 *
 * @author nona9961
 */
public class SubOrder {

    /**
     * 子订单主键（Snowflake，sub_order 主键，聚合根标识）
     */
    private final Long id;

    /**
     * 归属主订单 ID（业务关联列，跨聚合引用 ID——不持主单对象）
     */
    private final Long masterOrderId;

    /**
     * 归属店铺 ID（tenant=shopId，创建时定型不可变）
     */
    private final Long shopId;

    /**
     * 子订单号（TD-13 业务单号，sub_order_no 唯一）
     */
    private final String subOrderNo;

    /**
     * 收货地址快照（不可变 VO，创建时固化）
     */
    private final AddressSnapshot address;

    /**
     * 金额明细（不可变 VO：商品总额/运费/实付）
     */
    private final AmountDetail amount;

    /**
     * 订单项快照集合（不可变：创建时固化，无条目变更路径）
     */
    private final List<OrderItem> items;

    /**
     * 履约状态（状态机唯一可变位，迁移经聚合方法）
     */
    private SubOrderStatus status;

    /**
     * 运单 ID（可空：发货经 markShipped 定型后非空；跨域引用 ID）
     */
    private Long waybillId;

    /**
     * 下单时间（主表 create_time 审计时间，LocalDateTime 墙钟语义；
     * 冻结契约补充：卖家订单列表/详情展示面（WU-47 约定 createTime
     * 字段必填）消费）。
     * <p>
     * 取值纪律（MerchantApplication 先例同构）：创建路径新建实例为 null
     * （JPA auditing 在持久化时填充主表 create_time，转换器不负责），
     * 装载路径由 {@code SubOrderConvertor} 从 PO 回填——持久化装载后
     * 恒非空。只读字段，无任何变更路径。
     */
    private final LocalDateTime createTime;

    /**
     * 创建构造器（仅工厂调用）：状态定型为待支付、运单引用为空。
     *
     * @param id            子订单主键
     * @param masterOrderId 归属主订单 ID（必填）
     * @param shopId        归属店铺 ID（必填）
     * @param subOrderNo    子订单号（必填非空）
     * @param address       收货地址快照（必填）
     * @param amount        金额明细（必填，商品总额须与订单项合计自洽）
     * @param items         订单项集合（必填非空，至少一项）
     */
    public SubOrder(Long id, Long masterOrderId, Long shopId, String subOrderNo,
                    AddressSnapshot address, AmountDetail amount, List<OrderItem> items) {
        this(id, masterOrderId, shopId, subOrderNo, address, amount, items,
                SubOrderStatus.PENDING_PAYMENT, null);
    }

    /**
     * 装载构造器（仅仓储重建/转换器装载调用）：以持久化状态恢复聚合。
     * <p>
     * 装载路径同样守卫形态不变量（必填/金额自洽/条目非空）防脏数据——
     * 金额自洽为创建期锁定的硬不变量，库中异常数据立即暴露；
     * 不执行写路径校验（状态迁移守卫属业务写入校验，装载不适用）。
     * <p>
     * 下单时间（createTime）由重载装载构造承载（本构造委托传 null）——
     * 转换器装载路径请使用带 createTime 的装载构造回填主表审计时间。
     *
     * @param id            子订单主键
     * @param masterOrderId 归属主订单 ID
     * @param shopId        归属店铺 ID
     * @param subOrderNo    子订单号
     * @param address       收货地址快照
     * @param amount        金额明细
     * @param items         订单项集合
     * @param status        履约状态（持久化值）
     * @param waybillId     运单 ID（可空）
     */
    public SubOrder(Long id, Long masterOrderId, Long shopId, String subOrderNo,
                    AddressSnapshot address, AmountDetail amount, List<OrderItem> items,
                    SubOrderStatus status, Long waybillId) {
        this(id, masterOrderId, shopId, subOrderNo, address, amount, items,
                status, waybillId, null);
    }

    /**
     * 装载构造器（仅仓储重建/转换器装载调用，含下单时间回填）：以持久化
     * 状态恢复聚合（现有装载构造委托本构造，createTime 传 null）。
     *
     * @param id            子订单主键
     * @param masterOrderId 归属主订单 ID
     * @param shopId        归属店铺 ID
     * @param subOrderNo    子订单号
     * @param address       收货地址快照
     * @param amount        金额明细
     * @param items         订单项集合
     * @param status        履约状态（持久化值）
     * @param waybillId     运单 ID（可空）
     * @param createTime    下单时间（主表审计时间；创建路径 null，装载路径回填）
     */
    public SubOrder(Long id, Long masterOrderId, Long shopId, String subOrderNo,
                    AddressSnapshot address, AmountDetail amount, List<OrderItem> items,
                    SubOrderStatus status, Long waybillId, LocalDateTime createTime) {
        BusinessAssert.assertNonNull(id, "子订单主键不能为空");
        BusinessAssert.assertNonNull(masterOrderId, "子订单归属主订单 ID 不能为空");
        BusinessAssert.assertNonNull(shopId, "子订单归属店铺 ID 不能为空");
        BusinessAssert.assertTrue(subOrderNo != null && !subOrderNo.isBlank(),
                "子订单号不能为空");
        BusinessAssert.assertNonNull(address, "子订单地址快照不能为空");
        BusinessAssert.assertNonNull(amount, "子订单金额明细不能为空");
        BusinessAssert.assertNonNull(items, "子订单订单项集合不能为空");
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SUB_EMPTY.code(),
                !items.isEmpty(), "子订单必须包含至少一个订单项");
        BusinessAssert.assertNonNull(status, "子订单状态不能为空");
        final long goodsTotal = items.stream().mapToLong(OrderItem::getSubtotal).sum();
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_SUB_AMOUNT_MISMATCH.code(),
                amount.getGoodsAmount() == goodsTotal,
                "子订单商品总额与订单项合计不符：{} != {}", amount.getGoodsAmount(), goodsTotal);
        this.id = id;
        this.masterOrderId = masterOrderId;
        this.shopId = shopId;
        this.subOrderNo = subOrderNo;
        this.address = address;
        this.amount = amount;
        this.items = List.copyOf(items);
        this.status = status;
        this.waybillId = waybillId;
        this.createTime = createTime;
    }

    /**
     * 子订单主键。
     *
     * @return 主键
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属主订单 ID。
     *
     * @return 主订单 ID
     */
    public Long getMasterOrderId() {
        return masterOrderId;
    }

    /**
     * 归属店铺 ID（tenant=shopId）。
     *
     * @return 店铺 ID
     */
    public Long getShopId() {
        return shopId;
    }

    /**
     * 子订单号。
     *
     * @return 子订单号
     */
    public String getSubOrderNo() {
        return subOrderNo;
    }

    /**
     * 收货地址快照。
     *
     * @return 地址快照（不可变）
     */
    public AddressSnapshot getAddress() {
        return address;
    }

    /**
     * 金额明细。
     *
     * @return 金额明细（不可变）
     */
    public AmountDetail getAmount() {
        return amount;
    }

    /**
     * 订单项快照集合（不可变视图：读取安全、外部不可改）。
     *
     * @return 订单项列表快照
     */
    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    /**
     * 履约状态。
     *
     * @return 状态
     */
    public SubOrderStatus getStatus() {
        return status;
    }

    /**
     * 运单 ID（可空：发货前为空）。
     *
     * @return 运单 ID；未发货为 null
     */
    public Long getWaybillId() {
        return waybillId;
    }

    /**
     * 下单时间（主表审计时间；创建路径新建实例为 null，持久化装载
     * 后由转换器回填恒非空）。
     *
     * @return 下单时间；未持久化新建实例返回 null
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 支付成功推进：待支付 → 已支付（整单支付语义——主单下全部子单经
     * 编排同事务推进；重复支付为非法迁移拒绝）。
     */
    public void markPaid() {
        if (status != SubOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅待支付子单可标记支付成功（重复支付为非法迁移）");
        }
        this.status = SubOrderStatus.PAID;
    }

    /**
     * 商家发货推进：已支付 → 已发货。
     * <p>
     * 守卫（收敛在本方法）：
     * <ol>
     *     <li>归属校验：操作者店铺必须等于子单归属店铺（不符抛
     *         {@code order.sub_shop_mismatch}）；</li>
     *     <li>运单必填：waybillId 为空拒绝（一子单一在途运单；守卫拒绝
     *         码 {@code order.sub_status_illegal}）；</li>
     *     <li>状态守卫：仅已支付可发货（未支付发货/重复发货为非法迁移，
     *         {@code order.sub_status_illegal}）。</li>
     * </ol>
     *
     * @param operatorShopId 操作者店铺 ID（归属校验锚点）
     * @param waybillId      运单 ID（必填，物流域创建后引用）
     */
    public void markShipped(Long operatorShopId, Long waybillId) {
        if (operatorShopId == null || !operatorShopId.equals(shopId)) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_SHOP_MISMATCH.code(),
                    "子单只能由归属店铺发货（操作者店铺与子单归属店铺不符）");
        }
        if (waybillId == null) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "发货必须携带运单 ID");
        }
        if (status != SubOrderStatus.PAID) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅已支付子单可发货");
        }
        this.status = SubOrderStatus.SHIPPED;
        this.waybillId = waybillId;
    }

    /**
     * 完成推进：已发货 → 已完成。
     * <p>
     * 触发源由调用方语义区分：确认收货（B9.3）与收货超时自动完成
     * （B9.4③）共用同一迁移（状态机语义相同，无需在聚合内区分）；
     * 未发货直接完成/重复完成为非法迁移拒绝。
     */
    public void markCompleted() {
        if (status != SubOrderStatus.SHIPPED) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅已发货子单可标记完成");
        }
        this.status = SubOrderStatus.COMPLETED;
    }

    /**
     * 待支付直接取消：待支付 → 已取消（B8.6 ①）。
     * <p>
     * 支付超时自动取消（B8.3）共用同一迁移（触发语义在调用方：超时引擎/
     * 主动取消编排）；库存回滚由编排在应用层事务内同批完成（本方法仅
     * 推进子单状态）。已支付/已发货取消为非法迁移拒绝（已支付走退款，
     * 已发货不可取消 B8.6 ③）。
     */
    public void cancel() {
        if (status != SubOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅待支付子单可取消（已支付子单取消走退款流程）");
        }
        this.status = SubOrderStatus.CANCELLED;
    }

    /**
     * 退款申请推进：已支付/已发货/已完成 → 退款中（B8.4，全阶段可退
     * D1-o4）。未支付退款为非法迁移拒绝；重复申请（退款中再退款）为
     * 非法迁移拒绝（退款单防重的语义由支付域承载）。
     */
    public void markRefunding() {
        if (status != SubOrderStatus.PAID && status != SubOrderStatus.SHIPPED
                && status != SubOrderStatus.COMPLETED) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅已支付/已发货/已完成的子单可申请退款");
        }
        this.status = SubOrderStatus.REFUNDING;
    }

    /**
     * 退款成功推进：退款中 → 已退款（终态；B8.5 订单置已退款）。
     * 未退款中状态推进为非法迁移拒绝（支付域回调幂等防线先行）。
     */
    public void markRefunded() {
        if (status != SubOrderStatus.REFUNDING) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅退款中子单可标记退款成功");
        }
        this.status = SubOrderStatus.REFUNDED;
    }

    /**
     * 发货超时自动关单：已支付 → 已关闭（终态；B9.4②）。
     * <p>
     * 语义：商家逾期未发货（已支付未发货子单）自动关单，退款编排由
     * 调用方（超时引擎用例）部署——资金侧状态由退款单状态机承载，
     * 本方法仅定格订单侧关单终态；未支付关单为非法迁移拒绝（支付超时
     * 走 cancel），重复关闭为非法迁移拒绝。
     */
    public void closeByTimeout() {
        if (status != SubOrderStatus.PAID) {
            throw new BusinessException(EcommerceBusinessCode.ORDER_SUB_STATUS_ILLEGAL.code(),
                    "仅已支付未发货子单可超时关单（支付超时走取消）");
        }
        this.status = SubOrderStatus.CLOSED;
    }
}