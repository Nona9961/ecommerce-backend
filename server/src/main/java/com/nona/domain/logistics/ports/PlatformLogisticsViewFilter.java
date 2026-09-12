package com.nona.domain.logistics.ports;

import com.nona.domain.order.entity.SubOrderStatus;

/**
 * 平台物流视图筛选条件（不可变值对象；null 字段 = 不过滤——平台全量
 * 视角）。
 * <p>
 * 筛选维度即平台监督列表的「按店铺/状态筛选」两项：
 * <ul>
 *     <li>{@code shopId}——店铺等值过滤（跨店铺全集下按店铺收敛）；</li>
 *     <li>{@code status}——子单履约状态过滤（行单元=子单，状态筛选
 *         即行主维度的履约状态值，如 PAID=未发货面/SHIPPED=已发货面/
 *         CLOSED=发货超时关单面）；强类型枚举，非法值不可表达。</li>
 * </ul>
 * 校验位说明：状态为强类型枚举（无字符串解析面），店铺为 ID 数值，
 * 本值对象无独立校验逻辑（非法值不可构造）。
 *
 * @param shopId 店铺 ID 过滤（null = 全部店铺）
 * @param status 子单履约状态过滤（null = 全部状态）
 */
public record PlatformLogisticsViewFilter(Long shopId, SubOrderStatus status) {
}