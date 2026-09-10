package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.InventoryLogView;
import com.nona.api.seller.StockView;
import com.nona.domain.catalog.repo.SkuProductView;
import com.nona.domain.catalog.repo.SkuProductViewRepository;
import com.nona.domain.inventory.entity.InventoryItem;
import com.nona.domain.inventory.entity.InventoryLog;
import com.nona.domain.inventory.repo.InventoryItemRepository;
import com.nona.domain.inventory.repo.InventoryLogRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 商家端库存查询用例（seller 面，WU-60 冻结；Spring 注册 @Service
 * 随绿阶段与实现同提交——55 同款纪律，避免无中间态启动）。
 * <p>
 * 编排语义（只读查询 + catalog join 装配面）：
 * <ul>
 *     <li><b>库存分页</b>：消费 55 冻结的 {@code InventoryItemRepository
 *         .listPaged/count}（当前店铺全集、主键升序、租户过滤 fail-closed）；
 *         行映射 = InventoryItem → {@link StockView}，商品维度展示字段
 *         （productId/productName/specSummary）经 catalog 投影仓储
 *         {@link SkuProductViewRepository} 批量 join（单查询投影，非逐
 *         商品聚合装载）；join 缺失（库存行存在但 SKU 投影不命中）按
 *         数据异常呈现（fail-closed，不静默降级 null 展示——两侧表驱动
 *         生命周期不同步属装配数据异常）；</li>
 *     <li><b>单行回显</b>（调整响应组装）：{@link #toView} 以调整后库存
 *         聚合装配单行 StockView（同一投影接口，单元素批量）；</li>
 *     <li><b>流水分页</b>：先按 SKU 定位库存行（租户过滤 fail-closed——
 *         不存在/跨店按 404 呈现，归属不泄露），再消费 {@code
 *         InventoryLogRepository.listBySkuPaged/countBySku}（append-only，
 *         新流水在前）映射 {@link InventoryLogView}（类型枚举名、三态
 *         快照透传、createTime = 审计时间字符串投影）；</li>
 *     <li><b>分页参数</b>：offset 换算经 {@link PageQuery#offset()}，
 *         非法分页参数守卫由仓储契约承载（55 冻结，本层透传）。</li>
 * </ul>
 *
 * @author nona9961
 */
@Service
public class SellerStockQuery {

    /**
     * 库存聚合仓储（店铺全集分页 + 按 SKU 定位）
     */
    private final InventoryItemRepository inventoryItemRepository;

    /**
     * 库存流水仓储（按 SKU 分页审计面）
     */
    private final InventoryLogRepository inventoryLogRepository;

    /**
     * catalog 投影仓储（商品摘要 join 面）
     */
    private final SkuProductViewRepository skuProductViewRepository;

    /**
     * 构造库存查询用例。
     *
     * @param inventoryItemRepository  库存聚合仓储
     * @param inventoryLogRepository   库存流水仓储
     * @param skuProductViewRepository catalog 投影仓储
     */
    public SellerStockQuery(InventoryItemRepository inventoryItemRepository,
                            InventoryLogRepository inventoryLogRepository,
                            SkuProductViewRepository skuProductViewRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.inventoryLogRepository = inventoryLogRepository;
        this.skuProductViewRepository = skuProductViewRepository;
    }

    /**
     * 库存三态分页（商品维度展示字段经 catalog join）。
     *
     * @param shopId 当前店铺 ID（认证上下文定位，必填）
     * @param page   分页请求（归一化）
     * @return 分页库存行（主键升序）；空页 = 空列表 + total 0
     */
    public PageResult<StockView> listPaged(Long shopId, PageQuery page) {
        final List<InventoryItem> items = inventoryItemRepository.listPaged(
                Math.toIntExact(page.offset()), page.pageSize());
        final long total = inventoryItemRepository.count();
        if (items.isEmpty()) {
            return PageResult.of(List.of(), total, page);
        }
        final Set<Long> skuIds = items.stream().map(InventoryItem::getSkuId)
                .collect(Collectors.toSet());
        final Map<Long, SkuProductView> views = skuProductViewRepository.listBySkuIds(skuIds)
                .stream().collect(Collectors.toMap(SkuProductView::skuId, Function.identity()));
        final List<StockView> rows = items.stream().map(item -> toView(shopId, item, views))
                .toList();
        return PageResult.of(rows, total, page);
    }

    /**
     * 库存行单行回显（调整响应组装用：库存聚合 → StockView，join 单元素）。
     *
     * @param shopId 当前店铺 ID（认证上下文定位，必填）
     * @param item   已装载库存聚合（调整后本请求视角三态）
     * @return 库存行（含商品摘要 join 字段）
     */
    public StockView toView(Long shopId, InventoryItem item) {
        final Map<Long, SkuProductView> views = skuProductViewRepository
                .listBySkuIds(Set.of(item.getSkuId()))
                .stream().collect(Collectors.toMap(SkuProductView::skuId, Function.identity()));
        return toView(shopId, item, views);
    }

    /**
     * 库存行 → 契约行（商品摘要 join 字段装配）。
     * <p>
     * join 缺失（库存行存在但 SKU 投影不命中 = 装配数据异常）按数据
     * 异常呈现（fail-closed，不静默降级 null 展示——两侧表驱动生命
     * 周期不同步；前端类型 productId/productName 非空契约）。
     *
     * @param shopId 当前店铺 ID（认证上下文定位，必填）
     * @param item   库存聚合
     * @param views  SKU 摘要投影映射（skuId → 视图）
     * @return 库存行（含商品摘要 join 字段）
     */
    private static StockView toView(Long shopId, InventoryItem item,
                                    Map<Long, SkuProductView> views) {
        final SkuProductView view = views.get(item.getSkuId());
        if (view == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_NOT_FOUND.code(),
                    "库存行商品投影缺失（装配数据异常）", 404);
        }
        return new StockView(item.getSkuId(), view.productId(), view.productName(),
                view.specSummary(), item.getAvailable(), item.getHeld(), item.getSold());
    }

    /**
     * 库存变动流水分页（按 SKU；append-only，新流水在前）。
     *
     * @param shopId 当前店铺 ID（认证上下文定位，必填）
     * @param skuId  目标 SKU ID（不存在或跨店铺按 404 呈现）
     * @param page   分页请求（归一化）
     * @return 分页流水行；无流水 = 空列表 + total 0
     */
    public PageResult<InventoryLogView> listLogs(Long shopId, Long skuId, PageQuery page) {
        // SKU 定位守卫：租户过滤 fail-closed（不存在/跨店按不存在呈现，
        // 归属不泄露）——与调整路径同定位口径
        final InventoryItem located = inventoryItemRepository.getBySkuId(skuId);
        if (located == null) {
            throw new BusinessException(EcommerceBusinessCode.INVENTORY_NOT_FOUND.code(),
                    "SKU 库存不存在", 404);
        }
        final List<InventoryLog> logs = inventoryLogRepository.listBySkuPaged(
                skuId, Math.toIntExact(page.offset()), page.pageSize());
        final long total = inventoryLogRepository.countBySku(skuId);
        return PageResult.of(logs.stream().map(SellerStockQuery::toLogView).toList(),
                total, page);
    }

    /**
     * 流水行 → 契约行（类型枚举名、前后三态快照透传、审计时间字符串
     * 投影——append-only 行全字段呈现）。
     *
     * @param log 流水行
     * @return 契约流水行
     */
    private static InventoryLogView toLogView(InventoryLog log) {
        return new InventoryLogView(
                log.getId(), log.getSkuId(), log.getType().name(), log.getDelta(),
                log.getOrderId(),
                log.getBeforeAvailable(), log.getBeforeHeld(), log.getBeforeSold(),
                log.getAfterAvailable(), log.getAfterHeld(), log.getAfterSold(),
                log.getOperator(), log.getReason(),
                log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
    }
}