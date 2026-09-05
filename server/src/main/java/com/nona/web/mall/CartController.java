package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.mall.AddCartRequest;
import com.nona.api.mall.CartApi;
import com.nona.api.mall.CartGroupView;
import com.nona.api.mall.CheckAllRequest;
import com.nona.api.mall.CheckedBatchRequest;
import com.nona.api.mall.UpdateQuantityRequest;
import com.nona.application.mall.CartUseCase;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家购物车 REST 控制器（/mall/cart，BUYER 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link CartUseCase}；当前
 * 买家 ID 从跟踪上下文取（认证过滤器已写入 JWT 主体，不来自请求体）。
 * 端点语义：加购 / 改量 / 移除 / 批量勾选 / 全选 / 分组列表（详见
 * {@link CartApi}）。购物车数据买家专属，跨买家互不可见。
 *
 * @author nona9961
 */
@RestController
public class CartController implements CartApi {

    /**
     * 购物车用例
     */
    private final CartUseCase cartUseCase;

    /**
     * 请求上下文（取当前登录买家 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造购物车控制器。
     *
     * @param cartUseCase          购物车用例
     * @param tenantContextAccessor 请求上下文
     */
    public CartController(CartUseCase cartUseCase, TenantContextAccessor tenantContextAccessor) {
        this.cartUseCase = cartUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/mall/cart")
    public HttpResponse<Void> add(@Valid @RequestBody AddCartRequest request) {
        cartUseCase.add(currentAccountId(), request);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/mall/cart/{skuId}")
    public HttpResponse<Void> changeQuantity(@PathVariable("skuId") Long skuId,
                                             @Valid @RequestBody UpdateQuantityRequest request) {
        cartUseCase.changeQuantity(currentAccountId(), skuId, request);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/mall/cart/{skuId}")
    public HttpResponse<Void> remove(@PathVariable("skuId") Long skuId) {
        cartUseCase.remove(currentAccountId(), skuId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/mall/cart/checked")
    public HttpResponse<Void> check(@Valid @RequestBody CheckedBatchRequest request) {
        cartUseCase.check(currentAccountId(), request);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/mall/cart/checked-all")
    public HttpResponse<Void> checkAll(@Valid @RequestBody CheckAllRequest request) {
        cartUseCase.checkAll(currentAccountId(), request);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/mall/cart")
    public HttpResponse<List<CartGroupView>> list() {
        return HttpResponse.ok(cartUseCase.list(currentAccountId()));
    }

    /**
     * 当前登录买家 ID（认证过滤器写入跟踪作用域的身份）。
     *
     * @return 买家账号 ID
     */
    private Long currentAccountId() {
        return Long.valueOf(tenantContextAccessor.getIdentity());
    }
}