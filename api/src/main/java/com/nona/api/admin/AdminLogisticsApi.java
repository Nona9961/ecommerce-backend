package com.nona.api.admin;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 平台物流总览契约（/admin/logistics，ADMIN 角色，WU-48 约定端点：GET
 * /admin/logistics?shopId=&amp;status=&amp;pageNum=&amp;pageSize=）。
 * <p>
 * 形态契约（Web 接线时按本接口实现 controller）：
 * <ul>
 *     <li>shopId/status 可选过滤（缺省 = 全部店铺/全部状态），分页字段
 *         对齐 PageQuery（pageNum 第 1 页起、默认 10、上限 100）；</li>
 *     <li>查询复用 WU-38 冻结的 {@code PlatformLogisticsViewService}
 *         （domain/logistics/ports，读 PG 镜像表跨店铺全集，平台级无租户
 *         过滤）；status 参数为子单履约状态枚举名（非法值 = 不过滤或返回
 *         空、由接线侧按强类型枚举承载）；</li>
 *     <li>行形状逐字段对齐 {@link AdminLogisticsViewItem}（与领域
 *         PlatformLogisticsViewItem 一一对应，枚举 name() 字符串化）。</li>
 * </ul>
 *
 * @author nona9961
 */
public interface AdminLogisticsApi {

    /**
     * 平台物流视图列表（分页，跨店铺全集）。
     *
     * @param shopId 店铺 ID 过滤（null = 全部店铺）
     * @param status 子单履约状态过滤（null/空 = 全部状态）
     * @param page   分页请求（归一化：第 1 页起、默认 10、上限 100）
     * @return 平台物流视图行分页结果
     */
    HttpResponse<PageResult<AdminLogisticsViewItem>> listLogistics(
            Long shopId, String status, PageQuery page);
}