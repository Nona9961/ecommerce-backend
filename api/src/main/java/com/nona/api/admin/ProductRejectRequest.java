package com.nona.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 商品审核驳回请求体：驳回必须附原因（商家据此修改重提）。
 *
 * @param reason 驳回原因（商家可据此修改重提）
 */
public record ProductRejectRequest(
        @NotBlank @Size(max = 512) String reason
) {
}