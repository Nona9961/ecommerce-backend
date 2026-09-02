package com.nona.web.admin;

import com.nona.api.HttpResponse;
import com.nona.api.admin.BrandApi;
import com.nona.api.admin.BrandItem;
import com.nona.api.admin.BrandRequest;
import com.nona.api.common.CatalogItemStatus;
import com.nona.application.admin.BrandUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 品牌库 REST 控制器（/admin/brands，ADMIN 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380 / 查询参数显式解析）+ 委托
 * {@link BrandUseCase}，不承载业务逻辑。列表状态参数为可选过滤
 * （缺省全部），非法值显式解析拒绝（400 generic.validation_failed）。
 * 删除与禁用均映射 {@code delete}/{@code disable} 用例（「删除」= 禁用软删）。
 *
 * @author nona9961
 */
@RestController
public class BrandController implements BrandApi {

    /**
     * 品牌用例
     */
    private final BrandUseCase brandUseCase;

    /**
     * 构造品牌控制器。
     *
     * @param brandUseCase 品牌用例
     */
    public BrandController(BrandUseCase brandUseCase) {
        this.brandUseCase = brandUseCase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/brands")
    public HttpResponse<BrandItem> create(@Valid @RequestBody BrandRequest request) {
        return HttpResponse.ok(brandUseCase.create(request));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 查询参数 status 为可选过滤（缺省返回全部）；非法值显式解析拒绝。
     */
    @Override
    @GetMapping("/admin/brands")
    public HttpResponse<List<BrandItem>> list(
            @RequestParam(value = "status", required = false) String status) {
        final CatalogItemStatus filter = status == null ? null : CatalogItemStatus.fromName(status);
        return HttpResponse.ok(brandUseCase.list(filter));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/admin/brands/{brandId}")
    public HttpResponse<BrandItem> update(@PathVariable("brandId") Long brandId,
                                          @Valid @RequestBody BrandRequest request) {
        return HttpResponse.ok(brandUseCase.update(brandId, request));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除 = 禁用软删（行保留，状态迁移 DISABLED）。
     */
    @Override
    @DeleteMapping("/admin/brands/{brandId}")
    public HttpResponse<Void> delete(@PathVariable("brandId") Long brandId) {
        brandUseCase.delete(brandId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/brands/{brandId}/disable")
    public HttpResponse<Void> disable(@PathVariable("brandId") Long brandId) {
        brandUseCase.disable(brandId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/brands/{brandId}/enable")
    public HttpResponse<Void> enable(@PathVariable("brandId") Long brandId) {
        brandUseCase.enable(brandId);
        return HttpResponse.ok();
    }
}