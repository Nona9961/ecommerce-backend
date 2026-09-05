package com.nona.application.mall;

import com.nona.api.mall.AddCartRequest;
import com.nona.api.mall.CartEntryView;
import com.nona.api.mall.CartGroupView;
import com.nona.api.mall.CheckAllRequest;
import com.nona.api.mall.CheckedBatchRequest;
import com.nona.api.mall.UpdateQuantityRequest;
import com.nona.domain.catalog.ports.ProductBuyerView;
import com.nona.domain.catalog.ports.ProductQueryFacade;
import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.domain.order.factory.CartFactory;
import com.nona.domain.order.repo.CartRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.CrossTenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物车用例（买家端）：加购 / 改量 / 移除 / 勾选 / 全选 / 分组列表编排。
 * <p>
 * 跨上下文协作：加购与改量经 {@link ProductQueryFacade}（目录域只读契约）
 * 校验商品在售、SKU 归属与可售上限——非在售商品按不存在呈现（目录侧
 * 404 语义透传，不泄露生命周期状态），可售上限数据源为买家商品视图的
 * SKU 可售量（与详情页展示同源，购物车为软校验，下单预占为最终防线——
 * 预占属下单编排，不在本域边界）。
 * <p>
 * 事务边界：写用例持有一个事务（买家锁 → 聚合装载 → 调用聚合方法变更
 * → 变更集落库），同买家写经 {@link CartRepository#lockBuyer} 串行化，
 * (buyer,sku) 唯一约束兜底；读用例不开写事务。租户语义：cart/cart_item
 * 为买家维度 global 表（无需读放行），加购/改量因读目录域租户表
 * （product/sku）须在读放行上下文内调用——放行（{@link CrossTenant}）
 * 只出现在用例方法，作用域 = 方法执行范围。
 * <p>
 * 当前买家身份由调用方（web 层）从认证上下文传入，不来自请求体。
 *
 * @author nona9961
 */
@Service
public class CartUseCase {

    /**
     * 购物车仓储
     */
    private final CartRepository cartRepository;

    /**
     * 购物车工厂
     */
    private final CartFactory cartFactory;

    /**
     * 商品查询门面（在售校验 + SKU 归属 + 可售上限 + 店铺信息）
     */
    private final ProductQueryFacade productQueryFacade;

    /**
     * 构造购物车用例。
     *
     * @param cartRepository     购物车仓储
     * @param cartFactory        购物车工厂
     * @param productQueryFacade 商品查询门面
     */
    public CartUseCase(CartRepository cartRepository, CartFactory cartFactory,
                       ProductQueryFacade productQueryFacade) {
        this.cartRepository = cartRepository;
        this.cartFactory = cartFactory;
        this.productQueryFacade = productQueryFacade;
    }

    /**
     * 加购（选 SKU + 数量）：商品在售校验 + SKU 归属校验 + 数量 ≤ 可售
     * 上限；同 SKU 已存在则数量累加（保持单条目）。
     * <p>
     * 编排定式：读买家商品视图（在售 + SKU 归属 + 可售量）→ 买家锁 →
     * 装载聚合 → 工厂建条目（默认勾选）→ 聚合 add（累加合并 + 上限断言）
     * → 变更集落库。
     *
     * @param buyerId 当前买家账号 ID（认证上下文）
     * @param request 加购请求（商品 / SKU / 数量）
     */
    @CrossTenant
    @Transactional
    public void add(Long buyerId, AddCartRequest request) {
        final ProductBuyerView view = productQueryFacade.getBuyerView(request.productId());
        final ProductBuyerView.Sku sku = requireSku(view, request.skuId());
        cartRepository.lockBuyer(buyerId);
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        final CartItem item = cartFactory.createItem(cart, request.productId(), request.skuId(),
                request.quantity(), true, view.shopId(),
                view.shop() == null ? null : view.shop().name());
        cart.add(item, sku.available());
        cartRepository.save(cart);
    }

    /**
     * 修改条目数量（按 SKU 定位，绝对量替换）：条目必须存在（404 提示
     * 列表过期）；新数量校验同加购（> 0 且 ≤ 可售上限，商品非在售拒绝
     * 改量）。
     * <p>
     * 编排定式：买家锁 → 装载聚合 → 按 SKU 取条目（不存在 404）→ 按条目
     * 商品读买家商品视图（在售 + 可售量）→ 聚合 updateQuantity（上限断言）
     * → 变更集落库。
     *
     * @param buyerId 当前买家账号 ID（认证上下文）
     * @param skuId   条目 SKU ID
     * @param request 新数量
     */
    @CrossTenant
    @Transactional
    public void changeQuantity(Long buyerId, Long skuId, UpdateQuantityRequest request) {
        cartRepository.lockBuyer(buyerId);
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        final CartItem item = cart.getBySkuId(skuId)
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.ORDER_CART_ITEM_NOT_FOUND.code(), "购物车条目不存在", 404));
        final ProductBuyerView view = productQueryFacade.getBuyerView(item.getProductId());
        final ProductBuyerView.Sku sku = requireSku(view, skuId);
        cart.updateQuantity(skuId, request.quantity(), sku.available());
        cartRepository.save(cart);
    }

    /**
     * 移除条目（按 SKU 定位）：目标不存在幂等成功（删除语义与收藏取消
     * 同构——重复点击/列表过期不报错）。
     * <p>
     * 编排定式：买家锁 → 装载聚合 → 聚合 remove（存在即移除）→ 变更集
     * 落库。
     *
     * @param buyerId 当前买家账号 ID（认证上下文）
     * @param skuId   条目 SKU ID
     */
    @Transactional
    public void remove(Long buyerId, Long skuId) {
        cartRepository.lockBuyer(buyerId);
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        cart.remove(skuId);
        cartRepository.save(cart);
    }

    /**
     * 批量勾选/取消勾选条目（按 SKU 列表定位）：目标条目必须存在（404
     * 提示列表过期）；勾选标记持久化（跨请求保持）。
     * <p>
     * 编排定式：买家锁 → 装载聚合 → 逐 SKU 聚合 toggleChecked（不存在
     * 404）→ 变更集落库。
     *
     * @param buyerId 当前买家账号 ID（认证上下文）
     * @param request 目标 SKU 列表 + 勾选状态
     */
    @Transactional
    public void check(Long buyerId, CheckedBatchRequest request) {
        cartRepository.lockBuyer(buyerId);
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        for (final Long skuId : request.skuIds()) {
            cart.toggleChecked(skuId, request.checked());
        }
        cartRepository.save(cart);
    }

    /**
     * 全选/全不选：全部条目标记统一置位（空购物车幂等成功）。
     * <p>
     * 编排定式：买家锁 → 装载聚合 → 聚合 checkAll → 变更集落库。
     *
     * @param buyerId 当前买家账号 ID（认证上下文）
     * @param request 勾选状态
     */
    @Transactional
    public void checkAll(Long buyerId, CheckAllRequest request) {
        cartRepository.lockBuyer(buyerId);
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        cart.checkAll(request.checked());
        cartRepository.save(cart);
    }

    /**
     * 购物车分组列表（按店铺分组展示语义）：条目按店铺分组，
     * 每组携带店铺名（创建时快照）与数量合计；条目含勾选标记（结算
     * 勾选集的展示面）。
     * <p>
     * 读路径：买家锁不适用（只读）；装载聚合后取条目快照在用例层分组
     * 组装——分组为展示编排（非聚合不变量），归用例层。商品展示信息
     * （名称/主图/价格）不在本列表承载（买家端按需读详情/试算）。
     *
     * @param buyerId 当前买家账号 ID（认证上下文）
     * @return 按店铺分组的有序列表（组间按首个条目加购序）；空购物车
     * 返回空列表
     */
    public List<CartGroupView> list(Long buyerId) {
        final Cart cart = cartRepository.getByBuyerId(buyerId);
        final Map<Long, List<CartItem>> grouped = new LinkedHashMap<>();
        for (final CartItem item : cart.snapshot()) {
            grouped.computeIfAbsent(item.getShopId(), ignored -> new ArrayList<>()).add(item);
        }
        return grouped.values().stream()
                .map(CartUseCase::toGroup)
                .toList();
    }

    /**
     * 从买家商品视图提取 SKU 行并断言归属：SKU 不属于请求商品按请求构造
     * 错误拒绝（400，订单域不感知商品细节）。
     *
     * @param view  买家商品视图（在售商品）
     * @param skuId SKU ID
     * @return 视图内 SKU 行（携带可售量，上限校验数据源）
     */
    private static ProductBuyerView.Sku requireSku(ProductBuyerView view, Long skuId) {
        return view.skus().stream()
                .filter(sku -> sku.skuId().equals(skuId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        EcommerceBusinessCode.ORDER_CART_SKU_NOT_FOUND.code(), "SKU 不属于该商品", 400));
    }

    /**
     * 组内条目 → 店铺分组视图：店铺名取组内首条目创建时快照（组内同源），
     * 合计为组内条目数量之和（金额合计属试算演进项，本列表不承载）。
     *
     * @param items 组内条目（按加购序）
     * @return 分组视图
     */
    private static CartGroupView toGroup(List<CartItem> items) {
        final CartItem first = items.get(0);
        final int total = items.stream().mapToInt(CartItem::getQuantity).sum();
        return new CartGroupView(first.getShopId(), first.getShopName(), total,
                items.stream()
                        .map(item -> new CartEntryView(item.getSkuId(), item.getProductId(),
                                item.getQuantity(), item.isChecked()))
                        .toList());
    }
}