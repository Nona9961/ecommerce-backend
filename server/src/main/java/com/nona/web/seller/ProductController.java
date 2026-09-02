package com.nona.web.seller;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.ProductApi;
import com.nona.api.seller.ProductAttributeItem;
import com.nona.api.seller.ProductAttributeRequest;
import com.nona.api.seller.ProductDetail;
import com.nona.api.seller.ProductDraftItem;
import com.nona.api.seller.ProductDraftRequest;
import com.nona.api.seller.ProductImageItem;
import com.nona.api.seller.ProductImageRequest;
import com.nona.application.seller.ProductUseCase;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.ThreadContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端商品草稿 REST 控制器（/seller/products…，SELLER 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link ProductUseCase}，不承载
 * 业务逻辑；当前店铺 ID 从 {@link ThreadContext} 租户字段取（认证过滤器已
 * 将账号关联的当前店铺写入请求租户，不来自请求体——商家只能操作自己的
 * 商品）。商品归属校验由用例层经租户过滤完成（跨店铺按不存在呈现）。
 *
 * @author nona9961
 */
@RestController
public class ProductController implements ProductApi {

    /**
     * 商品用例
     */
    private final ProductUseCase productUseCase;

    /**
     * 请求上下文（取当前店铺 ID）
     */
    private final ThreadContext threadContext;

    /**
     * 构造商品草稿控制器。
     *
     * @param productUseCase 商品用例
     * @param threadContext  请求上下文
     */
    public ProductController(ProductUseCase productUseCase, ThreadContext threadContext) {
        this.productUseCase = productUseCase;
        this.threadContext = threadContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/products")
    public HttpResponse<ProductDetail> createDraft(@Valid @RequestBody ProductDraftRequest request) {
        return HttpResponse.ok(productUseCase.createDraft(currentShopId(), request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/products")
    public HttpResponse<PageResult<ProductDraftItem>> listDrafts(PageQuery query) {
        return HttpResponse.ok(productUseCase.listDrafts(currentShopId(), query));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/products/{productId}")
    public HttpResponse<ProductDetail> getProduct(@PathVariable("productId") Long productId) {
        return HttpResponse.ok(productUseCase.detail(productId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/products/{productId}")
    public HttpResponse<ProductDetail> updateProduct(@PathVariable("productId") Long productId,
                                                     @Valid @RequestBody ProductDraftRequest request) {
        return HttpResponse.ok(productUseCase.update(productId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/seller/products/{productId}")
    public HttpResponse<Void> deleteProduct(@PathVariable("productId") Long productId) {
        productUseCase.delete(productId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/products/{productId}/images")
    public HttpResponse<ProductImageItem> addImage(@PathVariable("productId") Long productId,
                                                   @Valid @RequestBody ProductImageRequest request) {
        return HttpResponse.ok(productUseCase.addImage(productId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/seller/products/{productId}/images/{imageId}")
    public HttpResponse<Void> removeImage(@PathVariable("productId") Long productId,
                                          @PathVariable("imageId") Long imageId) {
        productUseCase.removeImage(productId, imageId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/products/{productId}/images/{imageId}/primary")
    public HttpResponse<ProductImageItem> setPrimaryImage(@PathVariable("productId") Long productId,
                                                          @PathVariable("imageId") Long imageId) {
        return HttpResponse.ok(productUseCase.setPrimaryImage(productId, imageId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/products/{productId}/attributes")
    public HttpResponse<ProductAttributeItem> addAttribute(@PathVariable("productId") Long productId,
                                                           @Valid @RequestBody ProductAttributeRequest request) {
        return HttpResponse.ok(productUseCase.addAttribute(productId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/products/{productId}/attributes/{attributeId}")
    public HttpResponse<ProductAttributeItem> updateAttribute(@PathVariable("productId") Long productId,
                                                              @PathVariable("attributeId") Long attributeId,
                                                              @Valid @RequestBody ProductAttributeRequest request) {
        return HttpResponse.ok(productUseCase.updateAttribute(productId, attributeId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/seller/products/{productId}/attributes/{attributeId}")
    public HttpResponse<Void> removeAttribute(@PathVariable("productId") Long productId,
                                              @PathVariable("attributeId") Long attributeId) {
        productUseCase.removeAttribute(productId, attributeId);
        return HttpResponse.ok();
    }

    /**
     * 当前店铺 ID（认证过滤器写入 ThreadContext.tenantID 的租户值=当前店铺 ID）。
     *
     * @return 店铺 ID
     */
    private Long currentShopId() {
        final String tenantId = threadContext.getTenantID();
        if (tenantId == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_SHOP_NOT_FOUND.code(),
                    "店铺不存在", 404);
        }
        return Long.valueOf(tenantId);
    }
}