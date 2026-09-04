package com.nona.api.seller;

import jakarta.validation.constraints.NotNull;

/**
 * 运费模板启用/停用请求体（商家端）。
 *
 * @param enabled true 启用 / false 停用（幂等）
 * @author nona9961
 */
public record FreightTemplateStatusRequest(
        @NotNull(message = "启用状态不能为空") Boolean enabled
) {
}