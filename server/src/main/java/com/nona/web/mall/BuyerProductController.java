package com.nona.web.mall;

import com.nona.api.HttpResponse;
import com.nona.api.mall.BuyerProductApi;
import com.nona.api.mall.BuyerProductDetail;
import com.nona.application.mall.ProductQueryUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家端商品查询控制器（/mall/products/{productId}，BUYER 角色）。
 * <p>
 * 控制器保持薄壳：委托 {@link ProductQueryUseCase}（读放行在应用层用例
 * 方法上，本层不放行）；端点返回 Http 语义与全局异常处理一致（非在售
 * 商品 404——ControllerAdvice 统一呈现）。
 *
 * @author nona9961
 */
@RestController
public class BuyerProductController implements BuyerProductApi {

    /**
     * 买家商品查询用例
     */
    private final ProductQueryUseCase productQueryUseCase;

    /**
     * 构造买家商品查询控制器。
     *
     * @param productQueryUseCase 买家商品查询用例
     */
    public BuyerProductController(ProductQueryUseCase productQueryUseCase) {
        this.productQueryUseCase = productQueryUseCase;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 
     */
    @Override
    @GetMapping("/mall/products/{productId}")
    public HttpResponse<BuyerProductDetail> getProduct(@PathVariable("productId") Long productId) {
        return HttpResponse.ok(productQueryUseCase.getProduct(productId));
    }
}
