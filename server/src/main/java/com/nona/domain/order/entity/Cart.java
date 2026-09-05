package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.BusinessAssert;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 买家购物车聚合根（买家维度，cart 主表 + cart_item 从表）：
 * 买家专属的待结算条目集合，一个买家一个购物车（cart 主表一行）。
 * <p>
 * 聚合根拥有独立主键 cartId（Snowflake，cart 表主键）；buyerId 为业务
 * 关联列（一个买家一个购物车，唯一约束）。从表条目行以 cart_id（rootId）
 * 关联本聚合，并冗余 buyer_id 承载 (buyer,sku) 唯一约束（同 SKU 单条目
 * 的持久化兜底）。
 * <p>
 * 关键不变量：
 * <ol>
 *     <li>同一买家同一 SKU 至多一个条目（重复加购数量累加合并）；</li>
 *     <li>条目数量 ≥ 1（数量变更拒绝非正）；</li>
 *     <li>条目数量 ≤ 可售上限（上限由用例层经跨上下文读入，聚合方法
 *         以参数断言收敛——域不感知库存数据源）；</li>
 *     <li>勾选标记经聚合方法唯一入口变更（持久化，跨请求保持）。</li>
 * </ol>
 * 不变量收敛在本聚合（add/updateQuantity/remove/toggleChecked/checkAll
 * 都经过统一迁移逻辑），外部只能经聚合方法变更购物车；条目字段变更方法
 * 仅对本聚合可见。并发防线：同买家写经仓储买家锁串行化（见
 * {@link com.nona.domain.order.repo.CartRepository#lockBuyer}），
 * (buyer,sku) 唯一约束兜底。
 *
 * @author nona9961
 */
public class Cart {

    /**
     * 购物车主键（Snowflake，聚合根标识）
     */
    private final Long cartId;

    /**
     * 归属买家账号 ID（业务关联列，cart.buyer_id 唯一）
     */
    private final Long buyerId;

    /**
     * 条目集合（保持加购顺序）
     */
    private final List<CartItem> items = new ArrayList<>();

    /**
     * 构造购物车（仅 Factory 与仓储加载重建调用）。
     *
     * @param cartId  购物车主键
     * @param buyerId 归属买家账号 ID
     */
    public Cart(Long cartId, Long buyerId) {
        this.cartId = cartId;
        this.buyerId = buyerId;
    }

    /**
     * 装载构造器（仅仓储重建/转换器装载调用）：以持久化条目集合恢复聚合。
     * <p>
     * 装载路径不执行业务校验（可售上限属业务写入校验——库存随时可变，
     * 已持久化条目数量可能超过当前可售量，购物车为软状态，仍应可见并
     * 待下单硬校验），仅装载后守卫集合不变量（同 SKU 唯一、数量非负）
     * 防脏数据。外部业务变更必须经聚合行为方法，本构造器不是变更入口。
     *
     * @param cartId  购物车主键
     * @param buyerId 归属买家账号 ID
     * @param items   持久化条目集合（装载序 = 持久化序）
     */
    public Cart(Long cartId, Long buyerId, List<CartItem> items) {
        this(cartId, buyerId);
        this.items.addAll(items);
        assertInvariants();
    }

    /**
     * 购物车主键。
     *
     * @return 主键
     */
    public Long getId() {
        return cartId;
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
     * 条目条数。
     *
     * @return 条数
     */
    public int size() {
        return items.size();
    }

    /**
     * 条目集合快照（不可变视图，读路径使用）。
     *
     * @return 条目快照
     */
    public List<CartItem> snapshot() {
        return List.copyOf(items);
    }

    /**
     * 按 SKU ID 取条目。
     *
     * @param skuId SKU ID
     * @return 条目；不存在返回空
     */
    public Optional<CartItem> getBySkuId(Long skuId) {
        return items.stream().filter(item -> item.getSkuId().equals(skuId)).findFirst();
    }

    /**
     * 新增条目：同 SKU 已存在时数量累加（合并为单条目），否则装载新条目；
     * 加购或累加后数量必须 ≤ 可售上限（超限拒绝本次写入）。
     *
     * @param item      新条目（工厂创建，含数量与勾选标记）
     * @param available 该 SKU 买家可见可售上限（用例层跨上下文读入）
     */
    public void add(CartItem item, int available) {
        final CartItem existing = getBySkuId(item.getSkuId()).orElse(null);
        if (existing != null) {
            final int merged = existing.getQuantity() + item.getQuantity();
            BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_CART_QUANTITY_EXCEEDS.code(),
                    merged <= available, "购物车累计数量超过可售上限：{}", merged);
            existing.mergeQuantity(item.getQuantity());
            return;
        }
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_CART_QUANTITY_EXCEEDS.code(),
                item.getQuantity() <= available, "加购数量超过可售上限：{}", item.getQuantity());
        items.add(item);
    }

    /**
     * 修改条目数量（绝对量替换）：目标条目必须存在（按 SKU 定位）；
     * 新数量必须 ≥ 1 且 ≤ 可售上限。
     *
     * @param skuId     目标条目 SKU ID
     * @param quantity  新数量
     * @param available 该 SKU 买家可见可售上限（用例层跨上下文读入）
     */
    public void updateQuantity(Long skuId, int quantity, int available) {
        final CartItem entry = getBySkuId(skuId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.ORDER_CART_ITEM_NOT_FOUND.code(), "购物车条目不存在", 404));
        BusinessAssert.assertTrue(quantity >= 1, "购物车条目数量必须为正数：{}", quantity);
        BusinessAssert.assertTrue(EcommerceBusinessCode.ORDER_CART_QUANTITY_EXCEEDS.code(),
                quantity <= available, "购物车数量超过可售上限：{}", quantity);
        entry.setQuantity(quantity);
    }

    /**
     * 移除条目（按 SKU 定位）：目标不存在幂等成功（重复点击/列表过期
     * 不报错）。
     *
     * @param skuId 目标条目 SKU ID
     */
    public void remove(Long skuId) {
        items.removeIf(item -> item.getSkuId().equals(skuId));
    }

    /**
     * 设置条目的结算勾选标记（按 SKU 定位）：目标条目必须存在；已是
     * 目标状态则幂等。
     *
     * @param skuId   目标条目 SKU ID
     * @param checked 勾选状态
     */
    public void toggleChecked(Long skuId, boolean checked) {
        final CartItem entry = getBySkuId(skuId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.ORDER_CART_ITEM_NOT_FOUND.code(), "购物车条目不存在", 404));
        entry.setChecked(checked);
    }

    /**
     * 全选/全不选：全部条目标记统一置位（空购物车幂等成功）。
     *
     * @param checked 勾选状态
     */
    public void checkAll(boolean checked) {
        items.forEach(item -> item.setChecked(checked));
    }

    /**
     * 勾选条目集合（结算勾选集：下单编排按此消费——跨店铺拆分由用例
     * 层完成）。仅用于设置后的读取，变更必须经
     * {@link #toggleChecked} / {@link #checkAll}。
     *
     * @return 勾选条目列表（保持加购序）；无勾选返回空列表
     */
    public List<CartItem> listChecked() {
        return items.stream().filter(CartItem::isChecked).toList();
    }

    /**
     * 不变量守卫（防御性，逻辑正确时恒成立）：同 SKU 无重复条目、
     * 条目数量非负。
     */
    private void assertInvariants() {
        final long distinctSku = items.stream().map(CartItem::getSkuId).distinct().count();
        BusinessAssert.assertTrue(distinctSku == items.size(), "购物车同 SKU 条目重复：{}", items.size());
        final boolean anyNonPositive = items.stream().anyMatch(item -> item.getQuantity() < 1);
        BusinessAssert.assertTrue(!anyNonPositive, "购物车条目数量非正");
    }
}