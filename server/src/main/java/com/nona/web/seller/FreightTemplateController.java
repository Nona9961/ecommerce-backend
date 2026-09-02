package com.nona.web.seller;

import com.nona.api.HttpResponse;
import com.nona.api.seller.FreightApi;
import com.nona.api.seller.FreightTemplateDetail;
import com.nona.api.seller.FreightTemplateRequest;
import com.nona.api.seller.FreightTemplateStatusRequest;
import com.nona.application.seller.FreightTemplateUseCase;
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

import java.util.List;

/**
 * 商家端运费模板 REST 控制器（/seller/freight-templates…，SELLER 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link FreightTemplateUseCase}，
 * 不承载业务逻辑；当前店铺 ID 从 {@link ThreadContext} 租户字段取
 * （认证过滤器已将账号关联的当前店铺写入请求租户，不来自请求体——
 * 商家只能操作自己店铺的模板）。
 *
 * @author nona9961
 */
@RestController
public class FreightTemplateController implements FreightApi {

    /**
     * 运费模板用例
     */
    private final FreightTemplateUseCase freightTemplateUseCase;

    /**
     * 请求上下文（取当前店铺 ID）
     */
    private final ThreadContext threadContext;

    /**
     * 构造运费模板控制器。
     *
     * @param freightTemplateUseCase 运费模板用例
     * @param threadContext          请求上下文
     */
    public FreightTemplateController(FreightTemplateUseCase freightTemplateUseCase,
                                     ThreadContext threadContext) {
        this.freightTemplateUseCase = freightTemplateUseCase;
        this.threadContext = threadContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/freight-templates")
    public HttpResponse<FreightTemplateDetail> createFreightTemplate(
            @Valid @RequestBody FreightTemplateRequest request) {
        return HttpResponse.ok(freightTemplateUseCase.create(currentShopId(), request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/freight-templates")
    public HttpResponse<List<FreightTemplateDetail>> listFreightTemplates() {
        return HttpResponse.ok(freightTemplateUseCase.list(currentShopId()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/freight-templates/{templateId}")
    public HttpResponse<FreightTemplateDetail> getFreightTemplate(
            @PathVariable("templateId") Long templateId) {
        return HttpResponse.ok(freightTemplateUseCase.detail(templateId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/freight-templates/{templateId}")
    public HttpResponse<FreightTemplateDetail> updateFreightTemplate(
            @PathVariable("templateId") Long templateId,
            @Valid @RequestBody FreightTemplateRequest request) {
        return HttpResponse.ok(freightTemplateUseCase.update(templateId, request));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/freight-templates/{templateId}/status")
    public HttpResponse<FreightTemplateDetail> setFreightTemplateStatus(
            @PathVariable("templateId") Long templateId,
            @Valid @RequestBody FreightTemplateStatusRequest request) {
        return HttpResponse.ok(freightTemplateUseCase.setStatus(templateId, request.enabled()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @DeleteMapping("/seller/freight-templates/{templateId}")
    public HttpResponse<Void> deleteFreightTemplate(@PathVariable("templateId") Long templateId) {
        freightTemplateUseCase.delete(templateId);
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