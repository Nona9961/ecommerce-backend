package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 商家发货请求体（WU-47 约定形状：POST /seller/sub-orders/{subOrderId}
 * /ship，对齐 ShipOrderUseCase.shipByMerchant——商家填物流公司+运单号
 * → 运单创建 + 子单发货推进同事务；幂等：已 SHIPPED 返回成功；归属/
 * 在途守卫由服务端承载）。
 *
 * @param company   承运公司（必填非空，运单创建定型）
 * @param trackingNo 运单号（必填非空，运单创建定型）
 * @author nona9961
 */
public record ShipRequest(
        @NotBlank(message = "承运公司不能为空") String company,
        @NotBlank(message = "运单号不能为空") String trackingNo
) {
}