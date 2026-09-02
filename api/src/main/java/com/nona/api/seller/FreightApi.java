package com.nona.api.seller;

import com.nona.api.HttpResponse;

import java.util.List;

/**
 * 商家端运费模板契约（/seller/freight-templates，SELLER 角色）：模板 CRUD 与
 * 启用/停用。
 * <p>
 * 三规则互斥（不支持组合）：FREE 包邮（恒 0）、PER_ITEM 按件
 * （单价 × 件数）、THRESHOLD_FREE 满额免邮（订单金额达阈值免邮，
 * 未达额收基础运费）。金额单位均为分。当前店铺由认证上下文定位
 * （请求租户=当前店铺 ID），跨店铺模板访问按不存在呈现（404，fail-closed）；
 * 停用模板禁止用于新订单计费（领域守卫，契约随计算器冻结供订单域消费）。
 * 删除为物理删除；模板被商品引用的删除守卫随商品域后续版本补齐。
 *
 * @author nona9961
 */
public interface FreightApi {

    /**
     * 创建运费模板（初始启用）。
     *
     * @param request 模板名称与规则（ruleType: FREE / PER_ITEM / THRESHOLD_FREE）
     * @return 模板详情（含分配的 ID）
     */
    HttpResponse<FreightTemplateDetail> createFreightTemplate(FreightTemplateRequest request);

    /**
     * 当前店铺运费模板列表（新模板在前）。
     *
     * @return 模板列表；无模板返回空列表
     */
    HttpResponse<List<FreightTemplateDetail>> listFreightTemplates();

    /**
     * 模板详情。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     * @return 模板详情
     */
    HttpResponse<FreightTemplateDetail> getFreightTemplate(Long templateId);

    /**
     * 更新模板规则（名称/规则/参数整体替换，状态保持）。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     * @param request    新规则
     * @return 更新后的模板详情
     */
    HttpResponse<FreightTemplateDetail> updateFreightTemplate(Long templateId, FreightTemplateRequest request);

    /**
     * 启用/停用模板（幂等；停用后新建订单不可用）。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     * @param request    目标启用状态
     * @return 更新后的模板详情
     */
    HttpResponse<FreightTemplateDetail> setFreightTemplateStatus(Long templateId, FreightTemplateStatusRequest request);

    /**
     * 删除模板（物理删除；商品引用守卫随商品域后补）。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> deleteFreightTemplate(Long templateId);
}