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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 购物车用例单元测试：加购（在售/归属/上限校验编排）、改量、移除、勾选、
 * 全选与分组列表编排的成功与失败场景。
 * <p>
 * 依赖以内存桩替身（仓储按买家索引 + 空簿创建语义；商品门面可编程返回
 * 视图或抛目录侧异常），用例构造器直接装配，验证编排契约（当前买家
 * 维度查询、变更集落库、跨上下文读）。购物车为软校验（可售上限为写入
 * 校验），下单预占为最终防线（属下单编排边界）。
 *
 * @author nona9961
 */
class CartUseCaseTest {

    /**
     * 测试买家（认证上下文传入的身份）
     */
    private static final long BUYER_ID = 10001L;

    /**
     * 另一买家（隔离断言用）
     */
    private static final long OTHER_BUYER = 10002L;

    /**
     * 测试购物车主键（固定值，逻辑不依赖具体主键）
     */
    private static final long CART_ID = 5001L;

    /**
     * 测试商品 ID
     */
    private static final long PRODUCT_A = 2001L;

    /**
     * 店铺 A（分组锚点）
     */
    private static final long SHOP_A = 4001L;

    /**
     * 店铺 B（分组锚点）
     */
    private static final long SHOP_B = 4002L;

    /**
     * 购物车工厂（真实对象，机械层）
     */
    private CartFactory factory;

    /**
     * 购物车仓储桩（内存实现）
     */
    private StubCartRepository repository;

    /**
     * 商品查询门面桩（可编程视图/异常）
     */
    private StubProductQueryFacade queryFacade;

    /**
     * 购物车用例（被测对象）
     */
    private CartUseCase useCase;

    /**
     * 每用例前重建桩与用例实例（用例无状态，桩隔离各测试数据）。
     */
    @BeforeEach
    void setUp() {
        factory = new CartFactory();
        repository = new StubCartRepository(factory);
        queryFacade = new StubProductQueryFacade();
        useCase = new CartUseCase(repository, factory, queryFacade);
    }

    /**
     * happy：加购新 SKU → 条目创建（数量/勾选默认/归属字段），变更集落库。
     */
    @Test
    @DisplayName("加购新 SKU 创建条目")
    void add_newSku_createsEntry() {
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 10)));

        useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 2));

        final Cart saved = repository.lastSaved();
        assertThat(saved).isNotNull();
        final CartItem entry = saved.getBySkuId(3001L).orElseThrow();
        assertThat(entry.getQuantity()).isEqualTo(2);
        assertThat(entry.isChecked()).isTrue();
        assertThat(entry.getShopId()).isEqualTo(SHOP_A);
        assertThat(entry.getProductId()).isEqualTo(PRODUCT_A);
    }

    /**
     * happy：同 SKU 重复加购数量累加，条目保持单条。
     */
    @Test
    @DisplayName("同 SKU 重复加购累加")
    void add_sameSku_accumulates() {
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 10)));

        useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 2));
        useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 3));

        final Cart saved = repository.lastSaved();
        assertThat(saved.size()).isEqualTo(1);
        assertThat(saved.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(5);
    }

    /**
     * happy：改量（绝对量替换）生效并落库。
     */
    @Test
    @DisplayName("改量生效")
    void changeQuantity_replacesQuantity() {
        repository.seed(cartWith(BUYER_ID, entry(3001L, 2, SHOP_A, true)));
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 10)));

        useCase.changeQuantity(BUYER_ID, 3001L, new UpdateQuantityRequest(5));

        final Cart saved = repository.lastSaved();
        assertThat(saved.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(saved.size()).isEqualTo(1);
    }

    /**
     * happy：移除条目生效并落库。
     */
    @Test
    @DisplayName("移除条目生效")
    void remove_removesEntry() {
        repository.seed(cartWith(BUYER_ID, entry(3001L, 2, SHOP_A, true), entry(3002L, 3, SHOP_A, true)));

        useCase.remove(BUYER_ID, 3001L);

        final Cart saved = repository.lastSaved();
        assertThat(saved.size()).isEqualTo(1);
        assertThat(saved.getBySkuId(3002L)).isPresent();
    }

    /**
     * happy：批量勾选指定条目生效并落库。
     */
    @Test
    @DisplayName("批量勾选条目")
    void check_marksEntries() {
        repository.seed(cartWith(BUYER_ID, entry(3001L, 2, SHOP_A, true), entry(3002L, 3, SHOP_A, false)));

        useCase.check(BUYER_ID, new CheckedBatchRequest(List.of(3001L, 3002L), true));

        final Cart saved = repository.lastSaved();
        assertThat(saved.snapshot()).allMatch(CartItem::isChecked);
    }

    /**
     * happy：全选/全不选统一置位全部条目。
     */
    @Test
    @DisplayName("全不选生效")
    void checkAll_clearsAll() {
        repository.seed(cartWith(BUYER_ID, entry(3001L, 2, SHOP_A, true), entry(3002L, 3, SHOP_B, true)));

        useCase.checkAll(BUYER_ID, new CheckAllRequest(false));

        final Cart saved = repository.lastSaved();
        assertThat(saved.snapshot()).noneMatch(CartItem::isChecked);
    }

    /**
     * happy：列表按店铺分组——组内条目、店铺名与数量合计（跨店铺混合）。
     */
    @Test
    @DisplayName("列表按店铺分组")
    void list_groupedByShop_withTotals() {
        repository.seed(cartWith(BUYER_ID,
                entry(3001L, 2, SHOP_A, true), entry(3002L, 3, SHOP_A, false), entry(3003L, 1, SHOP_B, true)));

        final List<CartGroupView> groups = useCase.list(BUYER_ID);

        assertThat(groups).hasSize(2);
        final CartGroupView groupA = groups.get(0);
        assertThat(groupA.shopId()).isEqualTo(SHOP_A);
        assertThat(groupA.shopName()).isEqualTo("店铺甲");
        assertThat(groupA.totalQuantity()).isEqualTo(5);
        assertThat(groupA.items()).extracting(CartEntryView::skuId).containsExactly(3001L, 3002L);
        final CartGroupView groupB = groups.get(1);
        assertThat(groupB.shopId()).isEqualTo(SHOP_B);
        assertThat(groupB.totalQuantity()).isEqualTo(1);
    }

    /**
     * critical：加购数量恰达可售上限放行。
     */
    @Test
    @DisplayName("加购恰达上限放行")
    void add_reachesCeiling_succeeds() {
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 10)));

        useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 10));

        final Cart saved = repository.lastSaved();
        assertThat(saved.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(10);
    }

    /**
     * critical：改量恰达可售上限放行。
     */
    @Test
    @DisplayName("改量恰达上限放行")
    void changeQuantity_reachesCeiling_succeeds() {
        repository.seed(cartWith(BUYER_ID, entry(3001L, 2, SHOP_A, true)));
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 10)));

        useCase.changeQuantity(BUYER_ID, 3001L, new UpdateQuantityRequest(10));

        final Cart saved = repository.lastSaved();
        assertThat(saved.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(10);
    }

    /**
     * critical：空购物车列表返回空分组。
     */
    @Test
    @DisplayName("空购物车列表为空")
    void list_emptyCart_returnsEmptyGroups() {
        final List<CartGroupView> groups = useCase.list(BUYER_ID);

        assertThat(groups).isEmpty();
    }

    /**
     * error：加购超过可售上限拒绝（409 冲突语义）。
     */
    @Test
    @DisplayName("加购超上限拒绝")
    void add_exceedsCeiling_rejects() {
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 5)));

        assertThatThrownBy(() -> useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 6)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：SKU 不属于请求商品拒绝（400 语义，请求与商品内容不匹配）。
     */
    @Test
    @DisplayName("SKU 不属于商品拒绝")
    void add_skuNotInProduct_rejects() {
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3002L, 10)));

        assertThatThrownBy(() -> useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 1)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：商品非在售按不存在拒绝（目录侧 404 语义透传，不泄露生命周期
     * 状态——已下架/草稿/待审核统一按不存在呈现）。
     */
    @Test
    @DisplayName("商品非在售按不存在拒绝")
    void add_productNotOnSale_translatesNotFound() {
        queryFacade.failsWith(new BusinessException("catalog.product_not_found", "商品不存在或已下架", 404));

        assertThatThrownBy(() -> useCase.add(BUYER_ID, new AddCartRequest(PRODUCT_A, 3001L, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getHttpStatus()).isEqualTo(404));
    }

    /**
     * error：改量目标条目不存在拒绝（404 语义，列表过期显式提示）。
     */
    @Test
    @DisplayName("改量目标条目不存在拒绝")
    void changeQuantity_missingItem_rejects() {
        assertThatThrownBy(() -> useCase.changeQuantity(BUYER_ID, 3001L, new UpdateQuantityRequest(2)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：改量超过可售上限拒绝（409 冲突语义）。
     */
    @Test
    @DisplayName("改量超上限拒绝")
    void changeQuantity_exceedsCeiling_rejects() {
        repository.seed(cartWith(BUYER_ID, entry(3001L, 2, SHOP_A, true)));
        queryFacade.returns(viewWith(PRODUCT_A, SHOP_A, "店铺甲", sku(3001L, 5)));

        assertThatThrownBy(() -> useCase.changeQuantity(BUYER_ID, 3001L, new UpdateQuantityRequest(6)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：移除不存在的条目幂等成功（删除语义，重复请求不报错）。
     */
    @Test
    @DisplayName("移除不存在条目幂等成功")
    void remove_missingItem_idempotent() {
        useCase.remove(BUYER_ID, 9999L);

        assertThat(repository.getByBuyerId(BUYER_ID).getBySkuId(9999L)).isEmpty();
    }

    /**
     * error：购物车买家隔离——其他买家的条目对当前买家不可见（列表只
     * 按当前买家维度查询）。
     */
    @Test
    @DisplayName("其他买家购物车不可见")
    void list_otherBuyer_cartNotVisible() {
        repository.seed(cartWith(OTHER_BUYER, entry(3001L, 2, SHOP_A, true)));

        final List<CartGroupView> groups = useCase.list(BUYER_ID);

        assertThat(groups).isEmpty();
        assertThat(repository.lastQueriedBuyer()).isEqualTo(BUYER_ID);
    }

    /**
     * 构造买家商品视图（最小形态：单商品单 SKU 集，运费未绑定）。
     *
     * @param productId 商品 ID
     * @param shopId    归属店铺 ID
     * @param shopName  店铺名
     * @param skus      SKU 行
     * @return 买家商品视图
     */
    private static ProductBuyerView viewWith(Long productId, Long shopId, String shopName,
                                             ProductBuyerView.Sku... skus) {
        return new ProductBuyerView(productId, shopId, "测试商品", "测试描述",
                List.of(), List.of(), List.of(), List.of(skus), null,
                new ProductBuyerView.Shop(shopId, shopName, null));
    }

    /**
     * 构造 SKU 行（价格固定 100 分，可售量显式可控——上限校验数据源）。
     *
     * @param skuId     SKU ID
     * @param available 可售量
     * @return SKU 行
     */
    private static ProductBuyerView.Sku sku(Long skuId, int available) {
        return new ProductBuyerView.Sku(skuId, "h" + skuId, "规格", 10000L, available);
    }

    /**
     * 构造含条目的购物车（经装载构造器恢复；主键固定，逻辑不依赖具体主键）。
     *
     * @param buyerId 归属买家
     * @param items   条目
     * @return 购物车
     */
    private static Cart cartWith(Long buyerId, CartItem... items) {
        return new Cart(CART_ID, buyerId, List.of(items));
    }

    /**
     * 构造购物车条目（直接构造，ID 显式可控）。
     *
     * @param skuId   SKU ID
     * @param qty     数量
     * @param shopId  归属店铺
     * @param checked 勾选标记
     * @return 条目
     */
    private static CartItem entry(Long skuId, int qty, Long shopId, boolean checked) {
        return new CartItem(skuId, CART_ID, BUYER_ID, PRODUCT_A, skuId, qty, checked,
                shopId, shopId == SHOP_A ? "店铺甲" : "店铺乙");
    }

    /**
     * 购物车仓储内存桩：按买家索引缓存购物车，模拟契约语义——无车时
     * 创建空车（聚合恒存在）、保存记录最近落库对象、买家锁与买家查询
     * 留痕（隔离断言用）。
     */
    private static final class StubCartRepository implements CartRepository {

        /**
         * 空车创建工厂
         */
        private final CartFactory cartFactory;

        /**
         * 按买家索引的购物车缓存
         */
        private final Map<Long, Cart> cartsByBuyer = new HashMap<>();

        /**
         * 最近保存的购物车（落库断言）
         */
        private Cart lastSaved;

        /**
         * 最近按买家查询的买家 ID（隔离断言）
         */
        private Long lastQueriedBuyer;

        /**
         * 构造仓储桩。
         *
         * @param cartFactory 空车创建工厂
         */
        StubCartRepository(CartFactory cartFactory) {
            this.cartFactory = cartFactory;
        }

        /**
         * 预置缓存购物车（种子数据）。
         *
         * @param cart 购物车
         */
        void seed(Cart cart) {
            cartsByBuyer.put(cart.getBuyerId(), cart);
        }

        /**
         * 最近保存的购物车。
         *
         * @return 购物车；未保存过返回 null
         */
        Cart lastSaved() {
            return lastSaved;
        }

        /**
         * 最近按买家查询的买家 ID。
         *
         * @return 买家 ID；未查询过返回 null
         */
        Long lastQueriedBuyer() {
            return lastQueriedBuyer;
        }

        /**
         * {@inheritDoc}
         * <p>
         * 模拟空簿语义：无车时经工厂创建空车并缓存（聚合恒存在）。
         */
        @Override
        public Cart getByBuyerId(Long buyerId) {
            lastQueriedBuyer = buyerId;
            return cartsByBuyer.computeIfAbsent(buyerId, cartFactory::createCart);
        }

        /**
         * {@inheritDoc}
         * <p>
         * 记录最近落库对象并覆写缓存（变更集语义由聚合 diff 承载，桩直接
         * 保存整体）。
         */
        @Override
        public boolean save(Cart cart) {
            lastSaved = cart;
            cartsByBuyer.put(cart.getBuyerId(), cart);
            return true;
        }

        /**
         * {@inheritDoc}
         * <p>
         * 桩不模拟锁语义（单线程测试），仅留痕。
         */
        @Override
        public void lockBuyer(Long buyerId) {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Cart getByID(Long id) {
            return cartsByBuyer.values().stream()
                    .filter(cart -> cart.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int delete(Cart cart) {
            return cart == null ? 0 : deleteByID(cart.getId());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int deleteByID(Long id) {
            final int before = cartsByBuyer.size();
            cartsByBuyer.values().removeIf(cart -> cart.getId().equals(id));
            return before - cartsByBuyer.size();
        }
    }

    /**
     * 商品查询门面桩：可编程返回视图或抛目录侧异常（非在售 404 语义
     * 模拟）。
     */
    private static final class StubProductQueryFacade implements ProductQueryFacade {

        /**
         * 可编程视图
         */
        private ProductBuyerView view;

        /**
         * 可编程失败（优先级最高）
         */
        private BusinessException failure;

        /**
         * 设定正常返回视图。
         *
         * @param view 买家商品视图
         */
        void returns(ProductBuyerView view) {
            this.view = view;
            this.failure = null;
        }

        /**
         * 设定失败路径（目录侧异常透传模拟）。
         *
         * @param failure 业务异常
         */
        void failsWith(BusinessException failure) {
            this.failure = failure;
            this.view = null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ProductBuyerView getBuyerView(Long productId) {
            if (failure != null) {
                throw failure;
            }
            return view;
        }
    }
}