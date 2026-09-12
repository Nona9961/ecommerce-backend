package com.nona.api.seller;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

/**
 * 商家端库存契约（/seller/inventory…，SELLER 角色，WU-47 约定端点）：
 * 库存三态分页查看（商品维度展示字段后端经 catalog join）/ 手工调整
 * 可售（每笔必记流水）/ 流水查看（append-only）。
 * <p>
 * 形态契约（Web 接线时按本接口实现 controller）：
 * <ul>
 *     <li>当前店铺由认证上下文定位；skuId 必须属于当前店铺，否则按
 *         不存在呈现（404，fail-closed）；</li>
 *     <li>调整对齐 InventoryUseCase.adjustStock：仅可售变动（delta 带
 *         符号、非零）、调整后非负由服务端拒绝（inventory.insufficient
 *         语义）、操作人由后端认证上下文定位（前端不传）、每笔调整必
 *         有流水（与调整同事务）；</li>
 *     <li>库存数量为整数件，无金额换算；productId/productName/
 *         specSummary 展示字段经 catalog join 提供（查询编排 =
 *         application/seller/SellerStockQuery）。</li>
 * </ul>
 *
 * @author nona9961
 */
public interface SellerStockApi {

    /**
     * 库存三态分页查询（商品维度展示字段后端经 catalog join 提供）。
     *
     * @param page 分页请求（归一化）
     * @return 分页库存行（SKU 维度，主键升序）
     */
    HttpResponse<PageResult<StockView>> listStock(PageQuery page);

    /**
     * 手工调整可售（delta 带符号；非零；调整后为负由服务端拒绝）。
     *
     * @param skuId   目标 SKU ID（必须属于当前店铺，否则 404）
     * @param request 调整量 + 可选原因
     * @return 调整后库存行（本请求视角三态）
     */
    HttpResponse<StockView> adjustStock(Long skuId, StockAdjustRequest request);

    /**
     * 库存变动流水分页（按 SKU；append-only，新流水在前）。
     *
     * @param skuId 目标 SKU ID（必须属于当前店铺，否则 404）
     * @param page  分页请求（归一化）
     * @return 分页流水行
     */
    HttpResponse<PageResult<InventoryLogView>> listStockLogs(Long skuId, PageQuery page);
}