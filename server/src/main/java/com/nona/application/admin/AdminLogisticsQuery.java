package com.nona.application.admin;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;
import com.nona.domain.logistics.ports.PlatformLogisticsViewItem;
import com.nona.domain.logistics.ports.PlatformLogisticsViewService;
import org.springframework.stereotype.Service;

/**
 * 平台物流总览查询用例（admin 面，WU-60 接线 WU-48 约定端点）。
 * <p>
 * 编排面：薄委托 WU-38 冻结的 {@link PlatformLogisticsViewService}
 * （读 PG 镜像表跨店铺全集，平台级无租户过滤；超时未发货标记判定收敛
 * 在领域服务内）——查询编排收敛在应用层（web → application → domain
 * 依赖纪律），web 层仅做行呈现适配（枚举/时间字符串化）。
 *
 * @author nona9961
 */
@Service
public class AdminLogisticsQuery {

    /**
     * 平台物流视图查询服务（WU-38 冻结契约）
     */
    private final PlatformLogisticsViewService logisticsViewService;

    /**
     * 构造平台物流总览查询用例。
     *
     * @param logisticsViewService 平台物流视图查询服务
     */
    public AdminLogisticsQuery(PlatformLogisticsViewService logisticsViewService) {
        this.logisticsViewService = logisticsViewService;
    }

    /**
     * 平台物流视图列表（分页，跨店铺全集；过滤条件透传）。
     *
     * @param filter 店铺/状态过滤（可空字段 = 不过滤）
     * @param page   分页请求（归一化）
     * @return 平台物流视图行分页结果
     */
    public PageResult<PlatformLogisticsViewItem> list(PlatformLogisticsViewFilter filter,
                                                      PageQuery page) {
        return logisticsViewService.list(filter, page);
    }
}