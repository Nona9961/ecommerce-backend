package com.nona.application.seller;

import com.nona.api.seller.FreightTemplateDetail;
import com.nona.api.seller.FreightTemplateRequest;
import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.factory.FreightTemplateFactory;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商家端运费模板用例：模板 CRUD 与启用/停用编排。
 * <p>
 * 当前店铺由认证上下文定位（controller 从跟踪上下文取租户 ID=当前店铺
 * 传入创建路径；模板归属随写门禁注入租户列）。事务边界：所有写路径
 * （创建/更新/启停/删除）在用例事务内完成「加载聚合 → 领域操作 →
 * 变更集落库」；读路径直接查询。跨店铺模板访问按不存在呈现（404——
 * 租户过滤 fail-closed，不泄露归属）。停用模板的新订单守卫由运费计算器
 * 承载（本用例不重复检查）；模板被商品引用的删除守卫随商品域后补。
 *
 * @author nona9961
 */
@Service
public class FreightTemplateUseCase {

    /**
     * 运费模板仓储
     */
    private final FreightTemplateRepository freightTemplateRepository;

    /**
     * 运费模板聚合工厂
     */
    private final FreightTemplateFactory freightTemplateFactory;

    /**
     * 构造运费模板用例。
     *
     * @param freightTemplateRepository 运费模板仓储
     * @param freightTemplateFactory    运费模板工厂
     */
    public FreightTemplateUseCase(FreightTemplateRepository freightTemplateRepository,
                                  FreightTemplateFactory freightTemplateFactory) {
        this.freightTemplateRepository = freightTemplateRepository;
        this.freightTemplateFactory = freightTemplateFactory;
    }

    /**
     * 创建运费模板（初始启用）：解析规则 → 工厂创建（ID 生成/归属定型）
     * → 落库。
     *
     * @param shopId  当前店铺 ID（认证上下文）
     * @param request 模板名称与规则
     * @return 模板详情
     */
    @Transactional
    public FreightTemplateDetail create(Long shopId, FreightTemplateRequest request) {
        final FreightTemplate template = freightTemplateFactory.createFreightTemplate(
                shopId, request.name(), parseRuleType(request.ruleType()),
                request.perItemPrice(), request.baseFreight(), request.freeThreshold());
        freightTemplateRepository.save(template);
        return toDetail(template);
    }

    /**
     * 当前店铺运费模板列表（新模板在前）。
     *
     * @param shopId 当前店铺 ID（认证上下文）
     * @return 模板详情列表；无模板返回空列表
     */
    public List<FreightTemplateDetail> list(Long shopId) {
        return freightTemplateRepository.listByShopId(shopId).stream()
                .map(FreightTemplateUseCase::toDetail)
                .toList();
    }

    /**
     * 模板详情（跨店铺模板按不存在呈现，404）。
     *
     * @param templateId 模板 ID
     * @return 模板详情
     */
    public FreightTemplateDetail detail(Long templateId) {
        return toDetail(requireTemplate(templateId));
    }

    /**
     * 更新模板规则：名称/规则/参数整体替换（状态保持）。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     * @param request    新规则
     * @return 更新后的模板详情
     */
    @Transactional
    public FreightTemplateDetail update(Long templateId, FreightTemplateRequest request) {
        final FreightTemplate template = requireTemplate(templateId);
        template.updateRules(request.name(), parseRuleType(request.ruleType()),
                request.perItemPrice(), request.baseFreight(), request.freeThreshold());
        freightTemplateRepository.save(template);
        return toDetail(template);
    }

    /**
     * 启用/停用模板（幂等；停用后新订单不可用——守卫在计算器）。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     * @param enabled    目标启用状态
     * @return 更新后的模板详情
     */
    @Transactional
    public FreightTemplateDetail setStatus(Long templateId, boolean enabled) {
        final FreightTemplate template = requireTemplate(templateId);
        if (enabled) {
            template.enable();
        } else {
            template.disable();
        }
        freightTemplateRepository.save(template);
        return toDetail(template);
    }

    /**
     * 删除模板（物理删除；商品引用守卫随商品域后补）。
     *
     * @param templateId 模板 ID（必须属于当前店铺，否则 404）
     */
    @Transactional
    public void delete(Long templateId) {
        final FreightTemplate template = requireTemplate(templateId);
        freightTemplateRepository.deleteByID(template.getId());
    }

    /**
     * 加载模板并断言存在（目标必须属于当前店铺——租户过滤下跨店铺
     * 加载为空，按 404 呈现，不泄露归属信息）。
     *
     * @param templateId 模板 ID
     * @return 模板聚合
     */
    private FreightTemplate requireTemplate(Long templateId) {
        final FreightTemplate template = freightTemplateRepository.getByID(templateId);
        if (template == null) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_NOT_FOUND.code(),
                    "运费模板不存在");
        }
        return template;
    }

    /**
     * 解析规则类型字符串（契约值 FREE / PER_ITEM / THRESHOLD_FREE）；
     * 未知值按规则配置非法呈现（400，领域校验兜底）。
     *
     * @param ruleType 规则类型字符串
     * @return 规则类型枚举
     */
    private static FreightRuleType parseRuleType(String ruleType) {
        try {
            return FreightRuleType.valueOf(ruleType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(EcommerceBusinessCode.CATALOG_FREIGHT_TEMPLATE_INVALID_RULES.code(),
                    "不支持的运费规则类型：" + ruleType);
        }
    }

    /**
     * 领域模板 → 契约模板详情。
     *
     * @param template 模板聚合
     * @return 模板详情
     */
    private static FreightTemplateDetail toDetail(FreightTemplate template) {
        return new FreightTemplateDetail(template.getId(), template.getShopId(), template.getName(),
                template.getRuleType().name(), template.getPerItemPrice(), template.getBaseFreight(),
                template.getFreeThreshold(), template.getStatus().name());
    }
}