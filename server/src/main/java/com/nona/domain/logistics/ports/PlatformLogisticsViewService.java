package com.nona.domain.logistics.ports;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 平台物流视图查询服务（logistics 域查询契约，纯读意图，P5.1 平台运营
 * 物流监督列表）。
 * <p>
 * 语义约束：
 * <ul>
 *     <li><b>数据源</b>：读 PG 镜像表（waybill / sub_order / shop——
 *         CDC 白名单镜像，最终一致，秒级延迟可接受——平台监督列表
 *         非买家即时要操作的关键路径数据，与搜索读模型同容忍纪律）；
 *         本服务不触达主库，无写路径；</li>
 *     <li><b>行单元与范围</b>：一行 = 一个子单（跨店铺全集，含未发货
 *         子单），子单的物流凭据（运单/承运/单号/物流状态）为行内附注
 *         （未发货子单附注为空）——「超时未发货」针对已支付未发货子单
 *         （不产生运单），只有行单元落在子单才可能呈现该异常标记；</li>
 *     <li><b>筛选</b>：店铺（shopId 等值）与履约状态（子单状态值，
 *         null = 不过滤，强类型枚举无非法值面）；</li>
 *     <li><b>排序</b>：固定按子单创建时间倒序（最新下单在前），一期
 *         不提供排序参数（YAGNI）；</li>
 *     <li><b>分页</b>：api 层 {@link PageQuery} 构造器归一化（页码从
 *         1 起、默认 10、上限 100）；</li>
 *     <li><b>租户语义</b>：平台级全局命中（运营管理员视角，无店铺归属
 *         过滤）——PG 侧无租户过滤（与搜索读模型同形态，平台跨店
 *         全集读面）；</li>
 *     <li><b>事务</b>：读路径不开写事务；无账号形态（平台监督不涉
 *         写后自读窗口，无路由覆盖面）。</li>
 * </ul>
 * 接口签名契约冻结：II 期若迁移物流仓（外部仓配系统）读，仅换实现
 * 不换调用方。
 *
 * @author nona9961
 */
public interface PlatformLogisticsViewService {

    /**
     * 平台物流视图列表（分页，跨店铺全集）。
     * <p>
     * 编排语义（实现留接线阶段）：
     * <ol>
     *     <li>仓储同条件两查：{@code search}（当前页行投影）+ {@code count}
     *         （命中总数，与 search 同筛选条件同口径）；</li>
     *     <li>行映射：仓储行 → 视图行，超时未发货标记按行级判定公式
     *         计算（见 {@link PlatformLogisticsViewItem#timeoutOverdue()}）；
     *         不命中返回空列表 + total 0；</li>
     *     <li>组装 {@link PageResult}（页码/条数回显请求值）。</li>
     * </ol>
     *
     * @param filter 筛选条件（店铺/履约状态；null 字段 = 不过滤）
     * @param page   分页请求（pageNum/pageSize 归一化：第 1 页起、
     *               默认 10、上限 100）
     * @return 平台物流视图行分页结果（跨店铺全集；空即空列表 + total 0）
     */
    PageResult<PlatformLogisticsViewItem> list(PlatformLogisticsViewFilter filter, PageQuery page);
}