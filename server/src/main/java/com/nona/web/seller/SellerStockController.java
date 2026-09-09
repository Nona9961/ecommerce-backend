package com.nona.web.seller;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.InventoryLogView;
import com.nona.api.seller.SellerStockApi;
import com.nona.api.seller.StockAdjustRequest;
import com.nona.api.seller.StockView;
import com.nona.application.seller.InventoryUseCase;
import com.nona.application.seller.SellerStockQuery;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端库存 REST 控制器（/seller/inventory…，SELLER 角色，WU-60 接线
 * WU-47 约定端点）：库存三态分页（商品维度展示字段经 catalog join）/
 * 手工调整可售（每笔必记流水）/ 流水查看（append-only）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380）+ 委托 {@link SellerStockQuery}
 * 与 {@link InventoryUseCase}，不承载业务逻辑；当前店铺 ID 从跟踪
 * 上下文租户字段取；调整操作人 = 认证上下文身份（前端不传，库存
 * 调整审计语义）；skuId 必须属于当前店铺否则按不存在呈现（404，
 * fail-closed——租户过滤兜底先行，归属不泄露）。
 *
 * @author nona9961
 */
@RestController
public class SellerStockController implements SellerStockApi {

    /**
     * 库存查询用例（分页 join / 单行回显 / 流水）
     */
    private final SellerStockQuery sellerStockQuery;

    /**
     * 库存操作用例（手工调整）
     */
    private final InventoryUseCase inventoryUseCase;

    /**
     * 请求上下文（取当前店铺 ID 与操作人身份）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造库存控制器。
     *
     * @param sellerStockQuery      库存查询用例
     * @param inventoryUseCase      库存操作用例
     * @param tenantContextAccessor 请求上下文
     */
    public SellerStockController(SellerStockQuery sellerStockQuery,
                                 InventoryUseCase inventoryUseCase,
                                 TenantContextAccessor tenantContextAccessor) {
        this.sellerStockQuery = sellerStockQuery;
        this.inventoryUseCase = inventoryUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/inventory")
    public HttpResponse<PageResult<StockView>> listStock(PageQuery page) {
        return HttpResponse.ok(sellerStockQuery.listPaged(currentShopId(), page));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PutMapping("/seller/inventory/{skuId}")
    public HttpResponse<StockView> adjustStock(@PathVariable("skuId") Long skuId,
                                               @Valid @RequestBody StockAdjustRequest request) {
        final InventoryItem item = inventoryUseCase.adjustStock(currentShopId(), skuId,
                request.delta(), currentIdentity(), request.reason());
        return HttpResponse.ok(sellerStockQuery.toView(currentShopId(), item));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/inventory/{skuId}/logs")
    public HttpResponse<PageResult<InventoryLogView>> listStockLogs(
            @PathVariable("skuId") Long skuId, PageQuery page) {
        return HttpResponse.ok(sellerStockQuery.listLogs(currentShopId(), skuId, page));
    }

    /**
     * 当前店铺 ID（认证过滤器写入跟踪作用域 tenantID 的租户值=当前
     * 店铺 ID）。
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

    /**
     * 当前操作人身份（认证上下文 identity，库存调整审计语义；缺失时由
     * 用例层拒绝——inventory.log_invalid）。
     *
     * @return 操作人身份
     */
    private String currentIdentity() {
        return tenantContextAccessor.getIdentity();
    }
}