package com.nona.domain.order;

import com.nona.api.mall.AddCartRequest;
import com.nona.api.mall.CartApi;
import com.nona.api.mall.CheckAllRequest;
import com.nona.api.mall.CheckedBatchRequest;
import com.nona.api.mall.UpdateQuantityRequest;
import com.nona.application.mall.CartUseCase;
import com.nona.domain.order.entity.Cart;
import com.nona.domain.order.entity.CartItem;
import com.nona.domain.order.repo.CartRepository;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.web.mall.CartController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 购物车契约钉测试（红阶段即绿）：领域行为签名、仓储契约、用例签名、
 * 错误码取值与 REST 端点路由的冻结校验——任一签名/路由漂移在此立即暴露
 * （防契约演进破坏消费方）。
 *
 * @author nona9961
 */
class CartContractTest {

    /**
     * error 码语义核对：购物车数量超过可售上限（409 冲突——写入被拒，
     * 与下单预占的最终防线语义区分）。
     */
    @Test
    @DisplayName("数量超上限错误码冻结")
    void quantityExceedsCode_frozen() {
        assertThat(EcommerceBusinessCode.ORDER_CART_QUANTITY_EXCEEDS.code())
                .isEqualTo("order.cart_quantity_exceeds");
        assertThat(EcommerceBusinessCode.defaultStatus(EcommerceBusinessCode.ORDER_CART_QUANTITY_EXCEEDS.code()))
                .isEqualTo(409);
    }

    /**
     * error 码语义核对：改量/勾选目标条目不存在（404——列表过期显式提示）。
     */
    @Test
    @DisplayName("条目不存在错误码冻结")
    void itemNotFoundCode_frozen() {
        assertThat(EcommerceBusinessCode.ORDER_CART_ITEM_NOT_FOUND.code())
                .isEqualTo("order.cart_item_not_found");
        assertThat(EcommerceBusinessCode.defaultStatus(EcommerceBusinessCode.ORDER_CART_ITEM_NOT_FOUND.code()))
                .isEqualTo(404);
    }

    /**
     * error 码语义核对：SKU 不属于请求商品（400——请求构造错误）。
     */
    @Test
    @DisplayName("SKU 不可购错误码冻结")
    void skuNotFoundCode_frozen() {
        assertThat(EcommerceBusinessCode.ORDER_CART_SKU_NOT_FOUND.code())
                .isEqualTo("order.cart_sku_not_found");
        assertThat(EcommerceBusinessCode.defaultStatus(EcommerceBusinessCode.ORDER_CART_SKU_NOT_FOUND.code()))
                .isEqualTo(400);
    }

    /**
     * 契约核对：聚合行为方法签名（加购含可售上限参数、改量为绝对量）
     * 与装载构造器（持久化恢复通道）冻结。
     */
    @Test
    @DisplayName("聚合行为方法签名冻结")
    void cartBehaviorSignatures_frozen() throws Exception {
        final Class<Cart> type = Cart.class;
        type.getDeclaredMethod("add", CartItem.class, int.class);
        type.getDeclaredMethod("updateQuantity", Long.class, int.class, int.class);
        type.getDeclaredMethod("remove", Long.class);
        type.getDeclaredMethod("toggleChecked", Long.class, boolean.class);
        type.getDeclaredMethod("checkAll", boolean.class);
        type.getDeclaredMethod("listChecked");
        type.getConstructor(Long.class, Long.class, java.util.List.class);
    }

    /**
     * 契约核对：仓储接口签名（买家维度加载 + 买家写锁）冻结。
     */
    @Test
    @DisplayName("仓储契约签名冻结")
    void repositorySignatures_frozen() throws Exception {
        final Class<CartRepository> type = CartRepository.class;
        assertThat(type.getMethod("getByBuyerId", Long.class).getReturnType())
                .isEqualTo(Cart.class);
        type.getMethod("lockBuyer", Long.class);
        type.getMethod("save", Cart.class).getReturnType();
    }

    /**
     * 契约核对：用例编排签名冻结（当前买家维度 + 契约请求形态）。
     */
    @Test
    @DisplayName("用例契约签名冻结")
    void useCaseSignatures_frozen() throws Exception {
        final Class<CartUseCase> type = CartUseCase.class;
        type.getDeclaredMethod("add", Long.class, AddCartRequest.class);
        type.getDeclaredMethod("changeQuantity", Long.class, Long.class, UpdateQuantityRequest.class);
        type.getDeclaredMethod("remove", Long.class, Long.class);
        type.getDeclaredMethod("check", Long.class, CheckedBatchRequest.class);
        type.getDeclaredMethod("checkAll", Long.class, CheckAllRequest.class);
        type.getDeclaredMethod("list", Long.class);
    }

    /**
     * 契约核对：REST 端点路由冻结（HTTP 动词 + 路径）。
     */
    @Test
    @DisplayName("端点路由冻结")
    void endpointRoutes_frozen() throws Exception {
        assertThat(CartController.class.getInterfaces()).contains(CartApi.class);

        final Method add = CartController.class.getMethod("add", AddCartRequest.class);
        assertThat(add.getAnnotation(PostMapping.class).value()).containsExactly("/mall/cart");

        final Method changeQuantity = CartController.class.getMethod(
                "changeQuantity", Long.class, UpdateQuantityRequest.class);
        assertThat(changeQuantity.getAnnotation(PutMapping.class).value())
                .containsExactly("/mall/cart/{skuId}");

        final Method remove = CartController.class.getMethod("remove", Long.class);
        assertThat(remove.getAnnotation(DeleteMapping.class).value())
                .containsExactly("/mall/cart/{skuId}");

        final Method check = CartController.class.getMethod("check", CheckedBatchRequest.class);
        assertThat(check.getAnnotation(PutMapping.class).value())
                .containsExactly("/mall/cart/checked");

        final Method checkAll = CartController.class.getMethod("checkAll", CheckAllRequest.class);
        assertThat(checkAll.getAnnotation(PutMapping.class).value())
                .containsExactly("/mall/cart/checked-all");

        final Method list = CartController.class.getMethod("list");
        assertThat(list.getAnnotation(GetMapping.class).value()).containsExactly("/mall/cart");
    }
}