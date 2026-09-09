package com.nona.web.seller;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.SellerOrderApi;
import com.nona.api.seller.SellerSubOrderDetail;
import com.nona.api.seller.SellerSubOrderItem;
import com.nona.api.seller.ShipRequest;
import com.nona.application.seller.SellerOrderQuery;
import com.nona.application.seller.ShipOrderUseCase;
import com.nona.domain.order.entity.SubOrderStatus;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TenantContextAccessor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

/**
 * 商家端本店订单 REST 控制器（/seller/orders…，SELLER 角色，WU-60 接线
 * WU-47 约定端点）：列表（状态筛选 + 分页）/ 详情（含运单概要）/ 发货
 * （物流公司+运单号 → 运单创建 + 子单发货推进同事务）。
 * <p>
 * 控制器保持薄壳：参数校验（JSR-380 / 查询参数显式解析）+ 委托
 * {@link SellerOrderQuery} 与 {@link ShipOrderUseCase}，不承载业务
 * 逻辑；当前店铺 ID 从跟踪上下文租户字段取（认证过滤器已写入账号
 * 关联的当前店铺，不来自请求体）。status 为子单状态枚举名逗号分隔
 * 多值（如 {@code REFUNDING,REFUNDED}；缺省 = 全部，非法值显式解析
 * 拒绝 400）；跨店铺访问按不存在呈现（404，fail-closed——归属不泄露）。
 *
 * @author nona9961
 */
@RestController
public class SellerOrderController implements SellerOrderApi {

    /**
     * 本店订单查询用例
     */
    private final SellerOrderQuery sellerOrderQuery;

    /**
     * 商家发货用例
     */
    private final ShipOrderUseCase shipOrderUseCase;

    /**
     * 请求上下文（取当前店铺 ID）
     */
    private final TenantContextAccessor tenantContextAccessor;

    /**
     * 构造本店订单控制器。
     *
     * @param sellerOrderQuery     本店订单查询用例
     * @param shipOrderUseCase     商家发货用例
     * @param tenantContextAccessor 请求上下文
     */
    public SellerOrderController(SellerOrderQuery sellerOrderQuery,
                                 ShipOrderUseCase shipOrderUseCase,
                                 TenantContextAccessor tenantContextAccessor) {
        this.sellerOrderQuery = sellerOrderQuery;
        this.shipOrderUseCase = shipOrderUseCase;
        this.tenantContextAccessor = tenantContextAccessor;
    }

    /**
     * {@inheritDoc}
     * <p>
     * status 值：子单状态枚举名，逗号分隔可传多值（Spring @RequestParam
     * List 天然支持逗号分隔拆分）；null/空 = 不过滤全量；非法值显式
     * 解析拒绝（400 generic.validation_failed）。
     */
    @Override
    @GetMapping("/seller/orders")
    public HttpResponse<PageResult<SellerSubOrderItem>> listOrders(
            @RequestParam(value = "status", required = false) Collection<String> statuses,
            PageQuery page) {
        final List<SubOrderStatus> filters = statuses == null ? List.of()
                : statuses.stream().map(SubOrderStatus::fromName).toList();
        return HttpResponse.ok(sellerOrderQuery.listPaged(currentShopId(), filters, page));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/seller/orders/{subOrderId}")
    public HttpResponse<SellerSubOrderDetail> getOrder(
            @PathVariable("subOrderId") Long subOrderId) {
        return HttpResponse.ok(sellerOrderQuery.detail(currentShopId(), subOrderId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/seller/sub-orders/{subOrderId}/ship")
    public HttpResponse<Void> ship(@PathVariable("subOrderId") Long subOrderId,
                                   @Valid @RequestBody ShipRequest request) {
        shipOrderUseCase.shipByMerchant(currentShopId(), subOrderId,
                request.company(), request.trackingNo());
        return HttpResponse.ok();
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
}