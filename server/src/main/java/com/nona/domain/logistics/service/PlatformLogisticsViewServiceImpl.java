package com.nona.domain.logistics.service;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.domain.logistics.ports.PlatformLogisticsViewFilter;
import com.nona.domain.logistics.ports.PlatformLogisticsViewItem;
import com.nona.domain.logistics.ports.PlatformLogisticsViewService;
import com.nona.domain.logistics.repo.PlatformLogisticsViewRepository;
import com.nona.domain.logistics.repo.PlatformLogisticsViewRow;
import com.nona.domain.order.entity.SubOrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 平台物流视图查询服务实现（P5.1 平台运营物流监督列表编排）。
 * <p>
 * 编排职责：
 * <ol>
 *     <li>条件透传：筛选（店铺/子单状态，强类型枚举无校验面）与分页
 *         原样传给读模型仓储（同条件两查：search + count，口径一致）；</li>
 *     <li>行映射：仓储行 → 视图行——超时未发货标记按行级判定公式
 *         计算（子单已支付且发货超时截止时间已到：{@code PAID} +
 *         {@code timeoutAt 非空} + {@code timeoutAt 不晚于当前时刻}，
 *         UTC 语义；已发货/未到期/无 deadline 记录均不标记；
 *         认领位不参与——处理中暂态，成功即清除）；</li>
 *     <li>结果组装：{@link PageResult}（页码/条数回显请求值；空结果
 *         空列表 + total 0）。</li>
 * </ol>
 * 数据源语义：读模型仓储静态装配 PG 镜像表（replica 通道），平台级
 * 全局命中（跨店铺全集，P5.1 ①）；本服务无账号形态（平台监督不涉
 * 写后自读窗口路由）。
 *
 * @author nona9961
 */
@Component
@RequiredArgsConstructor
public class PlatformLogisticsViewServiceImpl implements PlatformLogisticsViewService {

    /**
     * 平台物流视图读模型仓储（PG 镜像表读取，replica 数据源）。
     */
    private final PlatformLogisticsViewRepository platformLogisticsViewRepository;

    /**
     * {@inheritDoc}
     * <p>
     * 编排语义：条件透传（同条件两查：search 先、count 后——行查询
     * 失败即短路不计数）→ 行映射（超时未发货标记判定：
     * {@code PAID} 且 {@code timeoutAt} 在位且已到期，判定时刻 = 服务
     * 当前时刻（UTC）；判定公式与语义论证见
     * {@link com.nona.domain.logistics.ports.PlatformLogisticsViewItem}
     * 类注释，断言见服务单测）→ {@link PageResult} 组装。
     * <p>
     * 防御：仓储违约返回 null 行列表时按空列表兜底（不 NPE）；行内
     * 判定为纯内存计算。
     */
    @Override
    public PageResult<PlatformLogisticsViewItem> list(PlatformLogisticsViewFilter filter, PageQuery page) {
        final List<PlatformLogisticsViewRow> rows = platformLogisticsViewRepository.search(filter, page);
        final List<PlatformLogisticsViewItem> records = rows == null ? List.of()
                : rows.stream().filter(Objects::nonNull).map(this::toItem).toList();
        final long total = platformLogisticsViewRepository.count(filter);
        return PageResult.of(records, total, page);
    }

    /**
     * 平台物流视图行判定：超时未发货标记真值（行级公式落位）。
     *
     * @param row 仓储行投影（原始列值）
     * @return true = 已支付且截止时间在位且已到期
     */
    private static boolean isTimeoutOverdue(PlatformLogisticsViewRow row) {
        return row.subOrderStatus() == SubOrderStatus.PAID
                && row.timeoutAt() != null
                && !row.timeoutAt().isAfter(Instant.now());
    }

    /**
     * 行映射：仓储行投影 → 视图行（判定标记外全字段透传）。
     *
     * @param row 仓储行投影（非 null，防御在上游过滤）
     * @return 视图行
     */
    private PlatformLogisticsViewItem toItem(PlatformLogisticsViewRow row) {
        return new PlatformLogisticsViewItem(row.subOrderId(), row.subOrderNo(),
                row.masterOrderId(), row.shopId(), row.shopName(), row.subOrderStatus(),
                row.waybillId(), row.company(), row.trackingNo(), row.waybillStatus(),
                row.timeoutAt(), isTimeoutOverdue(row));
    }
}