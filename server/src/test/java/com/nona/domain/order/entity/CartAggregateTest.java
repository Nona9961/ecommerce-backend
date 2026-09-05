package com.nona.domain.order.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 购物车聚合单元测试：同 SKU 单条目（重复加购累加）、数量约束（≥ 1 且
 * ≤ 可售上限）、移除幂等、勾选语义（单条目/全选/勾选集）与跨店铺条目
 * 共存的成功与失败场景。
 * <p>
 * 聚合对象直接构造（ID 显式可控）；含条目的场景经装载构造器恢复（装载
 * 路径不校验可售上限——已持久化条目数量可能超过当前可售量，属购物车
 * 软状态）。
 *
 * @author nona9961
 */
class CartAggregateTest {

    /**
     * 测试购物车主键（固定值，聚合逻辑不依赖具体主键）
     */
    private static final long CART_ID = 1L;

    /**
     * 测试购物车归属买家（固定值）
     */
    private static final long BUYER_ID = 10001L;

    /**
     * 测试商品 ID（条目冗余的读锚点）
     */
    private static final long PRODUCT_ID = 2001L;

    /**
     * 店铺 A（分组锚点）
     */
    private static final long SHOP_A = 4001L;

    /**
     * 店铺 B（分组锚点）
     */
    private static final long SHOP_B = 4002L;

    /**
     * happy：首条加购装载（数量/勾选/归属/店铺字段完整）。
     */
    @Test
    @DisplayName("首条加购装载成功")
    void add_firstItem_loads() {
        final Cart cart = cart();
        cart.add(entry(3001L, 2, SHOP_A), 10);

        assertThat(cart.size()).isEqualTo(1);
        final CartItem entry = cart.getBySkuId(3001L).orElseThrow();
        assertThat(entry.getQuantity()).isEqualTo(2);
        assertThat(entry.isChecked()).isTrue();
        assertThat(entry.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(entry.getShopId()).isEqualTo(SHOP_A);
        assertThat(entry.getShopName()).isEqualTo("店铺甲");
    }

    /**
     * happy：同 SKU 重复加购数量累加，条目保持单条。
     */
    @Test
    @DisplayName("同 SKU 重复加购累加")
    void add_sameSku_accumulates() {
        final Cart cart = cart();
        cart.add(entry(3001L, 2, SHOP_A), 10);
        cart.add(entry(3001L, 3, SHOP_A), 10);

        assertThat(cart.size()).isEqualTo(1);
        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(5);
    }

    /**
     * happy：改量（绝对量替换）生效。
     */
    @Test
    @DisplayName("改量生效")
    void updateQuantity_setsNewQuantity() {
        final Cart cart = loadedCart(entry(3001L, 5, SHOP_A));

        cart.updateQuantity(3001L, 2, 10);

        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(2);
        assertThat(cart.size()).isEqualTo(1);
    }

    /**
     * happy：移除条目后购物车不再包含该 SKU。
     */
    @Test
    @DisplayName("移除条目成功")
    void remove_removesItem() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A), entry(3002L, 3, SHOP_A));

        cart.remove(3001L);

        assertThat(cart.size()).isEqualTo(1);
        assertThat(cart.getBySkuId(3002L)).isPresent();
        assertThat(cart.getBySkuId(3001L)).isEmpty();
    }

    /**
     * happy：勾选/取消勾选切换生效。
     */
    @Test
    @DisplayName("勾选与取消勾选切换")
    void toggleChecked_marksAndUnmarks() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        cart.toggleChecked(3001L, false);
        assertThat(cart.getBySkuId(3001L).orElseThrow().isChecked()).isFalse();

        cart.toggleChecked(3001L, true);
        assertThat(cart.getBySkuId(3001L).orElseThrow().isChecked()).isTrue();
    }

    /**
     * happy：全选/全不选统一置位全部条目。
     */
    @Test
    @DisplayName("全选与全不选")
    void checkAll_selectsAll_thenClearsAll() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A), entry(3002L, 3, SHOP_B));

        cart.checkAll(true);
        assertThat(cart.snapshot()).allMatch(CartItem::isChecked);

        cart.checkAll(false);
        assertThat(cart.snapshot()).noneMatch(CartItem::isChecked);
    }

    /**
     * happy：勾选集只含勾选条目（结算勾选集的读取面）。
     */
    @Test
    @DisplayName("勾选集只含勾选条目")
    void listChecked_returnsOnlyChecked() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A), entry(3002L, 3, SHOP_B));

        cart.toggleChecked(3002L, false);

        final List<CartItem> checked = cart.listChecked();

        assertThat(checked).extracting(CartItem::getSkuId).containsExactly(3001L);
    }

    /**
     * critical：最小合法数量 1 加购成功。
     */
    @Test
    @DisplayName("数量 1 加购成功（边界）")
    void add_minimumQuantity_one() {
        final Cart cart = cart();
        cart.add(entry(3001L, 1, SHOP_A), 10);

        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(1);
    }

    /**
     * critical：累加后数量恰达可售上限，放行（边界内）。
     */
    @Test
    @DisplayName("累加后恰达可售上限放行")
    void add_accumulate_reachesCeiling() {
        final Cart cart = cart();
        cart.add(entry(3001L, 4, SHOP_A), 10);

        cart.add(entry(3001L, 6, SHOP_A), 10);

        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(10);
    }

    /**
     * critical：改量恰达可售上限，放行（边界内）。
     */
    @Test
    @DisplayName("改量恰达可售上限放行")
    void updateQuantity_reachesCeiling() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        cart.updateQuantity(3001L, 10, 10);

        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(10);
    }

    /**
     * critical：移除最后一条后购物车为空。
     */
    @Test
    @DisplayName("移除最后一条后购物车为空")
    void remove_lastItem_leavesEmpty() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        cart.remove(3001L);

        assertThat(cart.size()).isZero();
        assertThat(cart.listChecked()).isEmpty();
    }

    /**
     * critical：无勾选条目时勾选集为空列表。
     */
    @Test
    @DisplayName("无勾选条目时勾选集为空")
    void listChecked_noneChecked_empty() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));
        cart.toggleChecked(3001L, false);

        assertThat(cart.listChecked()).isEmpty();
    }

    /**
     * critical：跨店铺条目共存于同一购物车（买家维度集合，分组属展示层）。
     */
    @Test
    @DisplayName("跨店铺条目共存")
    void crossShop_mixedItems_coexist() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A), entry(3002L, 3, SHOP_B), entry(3003L, 1, SHOP_B));

        assertThat(cart.size()).isEqualTo(3);
        assertThat(cart.snapshot()).extracting(CartItem::getShopId)
                .containsExactly(SHOP_A, SHOP_B, SHOP_B);
    }

    /**
     * critical：重复勾选同一状态幂等（无异常、无变化）。
     */
    @Test
    @DisplayName("重复勾选同一状态幂等")
    void toggleChecked_sameState_idempotent() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        cart.toggleChecked(3001L, true);
        cart.toggleChecked(3001L, true);

        assertThat(cart.getBySkuId(3001L).orElseThrow().isChecked()).isTrue();
        assertThat(cart.size()).isEqualTo(1);
    }

    /**
     * error：加购数量超过可售上限拒绝。
     */
    @Test
    @DisplayName("加购超可售上限拒绝")
    void add_exceedsCeiling_rejects() {
        final Cart cart = cart();
        assertThatThrownBy(() -> cart.add(entry(3001L, 11, SHOP_A), 10))
                .isInstanceOf(BusinessException.class);
        assertThat(cart.size()).isZero();
    }

    /**
     * error：累加后超过可售上限整体拒绝（条目保持原量）。
     */
    @Test
    @DisplayName("累加超可售上限整体拒绝")
    void add_accumulateExceedsCeiling_rejects() {
        final Cart cart = cart();
        cart.add(entry(3001L, 4, SHOP_A), 10);

        assertThatThrownBy(() -> cart.add(entry(3001L, 7, SHOP_A), 10))
                .isInstanceOf(BusinessException.class);
        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(4);
    }

    /**
     * error：改量超过可售上限拒绝（条目保持原量）。
     */
    @Test
    @DisplayName("改量超可售上限拒绝")
    void updateQuantity_exceedsCeiling_rejects() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        assertThatThrownBy(() -> cart.updateQuantity(3001L, 11, 10))
                .isInstanceOf(BusinessException.class);
        assertThat(cart.getBySkuId(3001L).orElseThrow().getQuantity()).isEqualTo(2);
    }

    /**
     * error：改量目标条目不存在拒绝（列表过期显式提示，404 语义）。
     */
    @Test
    @DisplayName("改量目标条目不存在拒绝")
    void updateQuantity_missingSku_rejects() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        assertThatThrownBy(() -> cart.updateQuantity(9999L, 2, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("条目不存在");
    }

    /**
     * error：勾选目标条目不存在拒绝（列表过期显式提示，404 语义）。
     */
    @Test
    @DisplayName("勾选目标条目不存在拒绝")
    void toggleChecked_missingSku_rejects() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        assertThatThrownBy(() -> cart.toggleChecked(9999L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("条目不存在");
    }

    /**
     * error：移除不存在的条目幂等成功（删除语义，重复请求不报错）。
     */
    @Test
    @DisplayName("移除不存在条目幂等成功")
    void remove_missingSku_idempotent() {
        final Cart cart = loadedCart(entry(3001L, 2, SHOP_A));

        cart.remove(9999L);

        assertThat(cart.size()).isEqualTo(1);
    }

    /**
     * 构造空购物车（直接构造，ID 显式可控）。
     *
     * @return 空购物车
     */
    private static Cart cart() {
        return new Cart(CART_ID, BUYER_ID);
    }

    /**
     * 构造含条目的购物车（经装载构造器恢复，ID 显式可控）。
     *
     * @param items 条目
     * @return 购物车
     */
    private static Cart loadedCart(CartItem... items) {
        return new Cart(CART_ID, BUYER_ID, List.of(items));
    }

    /**
     * 构造购物车条目（直接构造，ID 显式可控；默认勾选）。
     *
     * @param skuId  SKU ID
     * @param qty    数量
     * @param shopId 归属店铺 ID
     * @return 条目
     */
    private static CartItem entry(Long skuId, int qty, Long shopId) {
        return new CartItem(skuId, CART_ID, BUYER_ID, PRODUCT_ID, skuId,
                qty, true, shopId, shopId.equals(SHOP_A) ? "店铺甲" : "店铺乙");
    }
}