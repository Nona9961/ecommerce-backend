package com.nona.api.seller;

import jakarta.validation.constraints.NotBlank;

/**
 * 店铺信息更新请求体（商家端）：编辑店铺名称/logo/简介。
 * <p>
 * 店铺状态不接受商家编辑（状态由平台侧管理）；名称必填非空，
 * logo 与简介可空（清空即传 null）。
 *
 * @param name        店铺名称（必填）
 * @param logo        店铺 logo（可空）
 * @param description 店铺简介（可空）
 * @author nona9961
 */
public record ShopInfoRequest(
        @NotBlank(message = "店铺名称不能为空") String name,
        String logo,
        String description
) {
}