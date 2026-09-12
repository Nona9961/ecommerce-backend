package com.nona.domain.logistics.repo;

import com.nona.api.common.PageQuery;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;

import java.util.List;

/**
 * 平台物流视图读模型仓储（PG 镜像表读取契约，纯读，平台物流监督列表数据面）。
 * <p>
 * 只读 PG replica 数据源（静态装配白名单纪律：本仓储实现仅持有 replica
 * 命名参数模板，无运行时路由、无事务、无写路径）；三张镜像表
 * （waybill / sub_order / shop）已在 CDC 镜像白名单，行集合 = 跨店铺
 * 全集（PG 侧无租户过滤，天然平台全局——与搜索读模型同形态）。
 * <p>
 * 本仓储只负责条件拼装 + 固定排序 + 分页切片 + 列投影（行结构见
 * {@link PlatformLogisticsViewRow}）；筛选合法性由服务层保证（强类型
 * 枚举/null 即不过滤，无字符串解析面）。
 *
 * @author nona9961
 */
public interface PlatformLogisticsViewRepository {

    /**
     * 按条件查询当前页行投影。
     *
     * @param filter 筛选条件（店铺等值/子单状态等值；null = 不过滤）
     * @param page   分页请求（偏移量语义由 PageQuery.offset 承载）
     * @return 当前页行列表（按子单创建时间倒序，最新下单在前）；
     *         无命中返回空列表（非 null）
     */
    List<PlatformLogisticsViewRow> search(PlatformLogisticsViewFilter filter, PageQuery page);

    /**
     * 按条件统计命中总数（分页 total 用；与 {@link #search} 同条件同口径）。
     *
     * @param filter 筛选条件
     * @return 命中子单数
     */
    long count(PlatformLogisticsViewFilter filter);
}