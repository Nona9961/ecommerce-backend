package com.nona.api.mall;

import com.nona.api.HttpResponse;

import java.util.List;

/**
 * 买家收货地址簿契约：买家端地址 CRUD 与默认地址设置接口定义。
 * <p>
 * 服务端实现位于 server 模块（{@code com.nona.web.mall}），路由前缀 /mall/addresses，
 * 仅买家角色可访问（Security 链按门户路由）。所有操作以当前登录买家为归属维度，
 * 不允许操作他人地址（不存在的地址一律按 404 呈现，不泄露归属信息）。
 *
 * @author nona9961
 */
public interface AddressApi {

    /**
     * 地址列表：返回当前买家的全部收货地址（含默认标记）。
     *
     * @return 地址列表，无地址时为空列表
     */
    HttpResponse<List<AddressResponse>> list();

    /**
     * 新增收货地址：可携带默认标记（第一个默认地址直接生效；重复默认由新设覆盖旧默认）。
     *
     * @param request 地址信息（收件人/电话/省市区/详细地址必填）
     * @return 新建地址的完整信息（含分配的地址 ID 与默认标记）
     */
    HttpResponse<AddressResponse> add(AddressRequest request);

    /**
     * 编辑收货地址：更新地址字段（收件人/电话/省市区/详细地址），不涉及默认标记变更。
     *
     * @param id      地址 ID（必须属于当前买家，否则 404）
     * @param request 新的地址信息
     * @return 更新后的地址信息
     */
    HttpResponse<AddressResponse> update(Long id, AddressRequest request);

    /**
     * 删除收货地址：地址簿内移除；若删除的是默认地址且簿内仍有其他地址，
     * 自动提升最早一条为默认（避免地址簿落入无默认的可疑状态）。
     *
     * @param id 地址 ID（必须属于当前买家，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> delete(Long id);

    /**
     * 设置默认地址：同买家默认地址唯一，新设自动让位旧默认（幂等：已是默认则无操作）。
     *
     * @param id 地址 ID（必须属于当前买家，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> setDefault(Long id);
}