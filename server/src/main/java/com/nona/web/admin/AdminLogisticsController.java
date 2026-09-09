package com.nona.web.admin;

import com.nona.api.HttpResponse;
import com.nona.api.admin.AdminLogisticsApi;
import com.nona.api.admin.AdminLogisticsViewItem;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.application.admin.AdminLogisticsQuery;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;
import com.nona.domain.logistics.ports.PlatformLogisticsViewItem;
import com.nona.domain.order.entity.SubOrderStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台物流总览 REST 控制器（/admin/logistics，ADMIN 角色，WU-60 接线
 * WU-48 约定端点）：平台物流监督列表（跨店铺全集 + shopId/status 可选
 * 过滤 + 分页）。
 * <p>
 * 控制器保持薄壳：查询参数显式解析 + 委托
 * {@link AdminLogisticsQuery}（应用层查询用例，内部委托 WU-38 冻结的
 * {@code PlatformLogisticsViewService}），不承载业务逻辑。status 为子单
 * 履约状态枚举名（非法值显式解析拒绝 400 generic.validation_failed）；
 * 行映射 = 领域视图行 → api 呈现行（枚举以 name() 字符串承载、
 * Instant 以 ISO 8601 字符串承载——模型面零改动，呈现适配收敛在
 * web 层）。物流视图读 PG 镜像表跨店铺全集，平台级无租户过滤。
 *
 * @author nona9961
 */
@RestController
public class AdminLogisticsController implements AdminLogisticsApi {

    /**
     * 平台物流总览查询用例（委托 WU-38 冻结的物流视图服务）
     */
    private final AdminLogisticsQuery logisticsQuery;

    /**
     * 构造平台物流总览控制器。
     *
     * @param logisticsQuery 平台物流总览查询用例
     */
    public AdminLogisticsController(AdminLogisticsQuery logisticsQuery) {
        this.logisticsQuery = logisticsQuery;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @GetMapping("/admin/logistics")
    public HttpResponse<PageResult<AdminLogisticsViewItem>> listLogistics(
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestParam(value = "status", required = false) String status,
            PageQuery page) {
        final SubOrderStatus filter = status == null ? null : SubOrderStatus.fromName(status);
        final PageResult<PlatformLogisticsViewItem> result = logisticsQuery.list(
                new PlatformLogisticsViewFilter(shopId, filter), page);
        final PageResult<AdminLogisticsViewItem> view = new PageResult<>(
                result.records().stream().map(AdminLogisticsController::toItem).toList(),
                result.total(), result.pageNum(), result.pageSize());
        return HttpResponse.ok(view);
    }

    /**
     * 领域视图行 → api 呈现行（逐字段投影：枚举 name() 字符串化、
     * Instant → ISO 8601 字符串）。
     *
     * @param item 领域视图行
     * @return api 呈现行
     */
    private static AdminLogisticsViewItem toItem(PlatformLogisticsViewItem item) {
        return new AdminLogisticsViewItem(
                item.subOrderId(),
                item.subOrderNo(),
                item.masterOrderId(),
                item.shopId(),
                item.shopName(),
                item.subOrderStatus().name(),
                item.waybillId(),
                item.company(),
                item.trackingNo(),
                item.waybillStatus() == null ? null : item.waybillStatus().name(),
                item.timeoutAt() == null ? null : item.timeoutAt().toString(),
                item.timeoutOverdue());
    }
}