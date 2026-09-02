package com.nona.web.admin;

import com.nona.api.HttpResponse;
import com.nona.api.admin.PlatformCategoryApi;
import com.nona.api.admin.PlatformCategoryItem;
import com.nona.api.admin.PlatformCategoryRequest;
import com.nona.api.common.CatalogItemStatus;
import com.nona.application.admin.PlatformCategoryUseCase;
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
 * 平台分类 REST 控制器（/admin/categories，ADMIN 角色）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380 / 查询参数显式解析）+ 委托
 * {@link PlatformCategoryUseCase}，不承载业务逻辑。列表状态参数为可选过滤
 * （缺省全部），非法值显式解析拒绝（400 generic.validation_failed）。
 * 删除与禁用均映射 {@code delete}/{@code disable} 用例（「删除」= 禁用软删）。
 *
 * @author nona9961
 */
@RestController
public class PlatformCategoryController implements PlatformCategoryApi {

    /**
     * 平台分类用例
     */
    private final PlatformCategoryUseCase categoryUseCase;

    /**
     * 构造平台分类控制器。
     *
     * @param categoryUseCase 平台分类用例
     */
    public PlatformCategoryController(PlatformCategoryUseCase categoryUseCase) {
        this.categoryUseCase = categoryUseCase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/categories")
    public HttpResponse<PlatformCategoryItem> create(@Valid @RequestBody PlatformCategoryRequest request) {
        return HttpResponse.ok(categoryUseCase.create(request));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 查询参数 status 为可选过滤（缺省返回全部）；非法值显式解析拒绝。
     */
    @Override
    @GetMapping("/admin/categories")
    public HttpResponse<List<PlatformCategoryItem>> list(
            @RequestParam(value = "status", required = false) String status) {
        final CatalogItemStatus filter = status == null ? null : CatalogItemStatus.fromName(status);
        return HttpResponse.ok(categoryUseCase.list(filter));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/admin/categories/{categoryId}")
    public HttpResponse<PlatformCategoryItem> update(@PathVariable("categoryId") Long categoryId,
                                                     @Valid @RequestBody PlatformCategoryRequest request) {
        return HttpResponse.ok(categoryUseCase.update(categoryId, request));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除 = 禁用软删（行保留，状态迁移 DISABLED）。
     */
    @Override
    @DeleteMapping("/admin/categories/{categoryId}")
    public HttpResponse<Void> delete(@PathVariable("categoryId") Long categoryId) {
        categoryUseCase.delete(categoryId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/categories/{categoryId}/disable")
    public HttpResponse<Void> disable(@PathVariable("categoryId") Long categoryId) {
        categoryUseCase.disable(categoryId);
        return HttpResponse.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/admin/categories/{categoryId}/enable")
    public HttpResponse<Void> enable(@PathVariable("categoryId") Long categoryId) {
        categoryUseCase.enable(categoryId);
        return HttpResponse.ok();
    }
}