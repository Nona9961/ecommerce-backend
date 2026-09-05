package com.nona.web.seller;

import com.nona.api.HttpResponse;
import com.nona.api.seller.ShopApi;
import com.nona.api.seller.ShopCategoryItem;
import com.nona.api.seller.ShopCategoryRequest;
import com.nona.api.seller.ShopDetail;
import com.nona.api.seller.ShopInfoRequest;
import com.nona.application.seller.ShopUseCase;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端店铺 REST 控制器（/seller/shop…，SELLER 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link ShopUseCase}，不承载业务逻辑；
 * 当前店铺 ID 从跟踪上下文 租户字段取（认证过滤器已将账号关联的
 * 当前店铺写入请求租户，不来自请求体——商家只能操作自己的店铺）。
 *
 * @author nona9961
 */
@RestController
public class ShopController implements ShopApi {

    /**
     * 店铺用例
     */
    private final ShopUseCase shopUseCase;

    /**
     * 请求上下文（取当前店铺 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造店铺控制器。
     *
     * @param shopUseCase    店铺用例
     * @param threadContext  请求上下文
     */
    public ShopController(ShopUseCase shopUseCase, TenantContextAccessor tenantContextAccessor) {
        this.shopUseCase = shopUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/shop")
    public HttpResponse<ShopDetail> getShop() {
        return HttpResponse.ok(shopUseCase.detail(currentShopId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/shop")
    public HttpResponse<ShopDetail> updateShop(@Valid @RequestBody ShopInfoRequest request) {
        return HttpResponse.ok(shopUseCase.updateInfo(currentShopId(), request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/shop/categories")
    public HttpResponse<ShopCategoryItem> addCategory(@Valid @RequestBody ShopCategoryRequest request) {
        return HttpResponse.ok(shopUseCase.addCategory(currentShopId(), request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/shop/categories/{categoryId}")
    public HttpResponse<ShopCategoryItem> renameCategory(@PathVariable("categoryId") Long categoryId,
                                                         @Valid @RequestBody ShopCategoryRequest request) {
        return HttpResponse.ok(shopUseCase.renameCategory(currentShopId(), categoryId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/seller/shop/categories/{categoryId}")
    public HttpResponse<Void> removeCategory(@PathVariable("categoryId") Long categoryId) {
        shopUseCase.removeCategory(currentShopId(), categoryId);
        return HttpResponse.ok();
    }

    /**
     * 当前店铺 ID（认证过滤器写入跟踪作用域 tenantID 的租户值=当前店铺 ID）。
     *
     * @return 店铺 ID
     */
    private Long currentShopId() {
        final String tenantId = tenantContextAccessor.getTenantID();
        if (tenantId == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_NOT_FOUND.code(),
                    "店铺不存在", 404);
        }
        return Long.valueOf(tenantId);
    }
}