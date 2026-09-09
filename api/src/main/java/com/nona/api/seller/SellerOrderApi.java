package com.nona.api.seller;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

import java.util.Collection;

/**
 * 商家端本店订单契约（/seller/orders…，SELLER 角色，WU-47 约定端点）：
 * 本店子订单列表（状态筛选 + 分页）/ 详情（商品快照/金额明细/地址/物流
 * 信息）/ 发货（物流公司+运单号 → 运单创建 + 子单发货推进同事务）。
 * <p>
 * 形态契约（Web 接线时按本接口实现 controller）：
 * <ul>
 *     <li>当前店铺由认证上下文定位（Controller 经租户上下文取 shopId，
 *         前端不传）；仅见本店子订单，跨店铺按不存在呈现（404，
 *         fail-closed——S2.3/G1.1 锚点）；</li>
 *     <li>列表 status 参数：子单履约状态枚举名逗号分隔多值（如
 *         REFUNDING,REFUNDED；空/缺省 = 全部），Spring @RequestParam
 *         List 天然支持逗号分隔；</li>
 *     <li>详情金额分、运单概要未发货为 null、createTime 为主表审计时间
 *         （IS0 8601 字符串投影）；</li>
 *     <li>发货幂等（已 SHIPPED 返回成功），归属/在途守卫由服务端承载
 *         （ShipOrderUseCase.shipByMerchant 接线）。</li>
 * </ul>
 * 查询编排 = {@code application/seller/SellerOrderQuery}（店铺分页/详情
 * + 运单概要，消费 55 冻结查询契约，只读零签名变更）。
 *
 * @author nona9961
 */
public interface SellerOrderApi {

    /**
     * 本店子订单列表（状态筛选 + 分页）。
     *
     * @param statuses 子订单状态过滤（枚举名集合，null/空 = 不过滤全量）
     * @param page     分页请求（归一化）
     * @return 分页本店子订单列表行（创建时间倒序）
     */
    HttpResponse<PageResult<SellerSubOrderItem>> listOrders(
            Collection<String> statuses, PageQuery page);

    /**
     * 本店子订单详情（商品快照/金额明细/地址/物流信息）。
     *
     * @param subOrderId 子订单 ID（不存在或跨店铺按 404 呈现）
     * @return 子订单详情（含运单概要，未发货为 null）
     */
    HttpResponse<SellerSubOrderDetail> getOrder(Long subOrderId);

    /**
     * 商家标记发货（已支付子单可发：填物流公司+运单号 → 运单生成 +
     * 发货推进；幂等——已 SHIPPED 返回成功）。
     *
     * @param subOrderId 子订单 ID（不存在或跨店铺按 404 呈现）
     * @param request    承运公司与运单号（必填非空）
     * @return 成功响应
     */
    HttpResponse<Void> ship(Long subOrderId, ShipRequest request);
}