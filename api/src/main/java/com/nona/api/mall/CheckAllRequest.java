package com.nona.api.mall;

import jakarta.validation.constraints.NotNull;

/**
 * 购物车全选/全不选请求体（PUT /mall/cart/checked-all）：批量设置
 * 全部条目的结算勾选标记（空购物车幂等成功）。
 *
 * @param checked 勾选状态（true 全选 / false 全不选）
 *
 * @author nona9961
 */
public record CheckAllRequest(
        @NotNull Boolean checked
) {
}