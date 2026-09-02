package com.nona.inf.persistence.repository;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.FreightTemplateConvertor;
import com.nona.inf.persistence.po.catalog.FreightTemplatePO;
import com.nona.inf.persistence.repository.jpa.FreightTemplateJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运费模板仓储落地：继承 {@link DifferRepository}（主表快照 + 变更追踪），
 * 主表 freight_template（tenant=shopId）单表单行，无集合子实体。
 * <p>
 * 读：按主表主键加载（track 快照）；保存：读 → track → calculateChanges →
 * 变更集驱动整行更新（单表聚合无子表可精细化，根行字段覆盖落库）；
 * 删除：deleteByID 返回真实删除条数（0/1 真实语义）。
 * 列表按店铺查询（租户过滤 + shop_id 定位，新模板在前；返回对象仅供读）。
 * <p>
 * 写路径的租户列由写门禁按请求上下文注入（商家请求 tenant=当前店铺），
 * 读路径由 Hibernate 租户过滤保证 fail-closed（跨店铺加载不到模板行）。
 *
 * @author nona9961
 */
@Component
public class FreightTemplateRepositoryImpl extends DifferRepository<FreightTemplate, FreightTemplatePO, Void>
        implements FreightTemplateRepository {

    /**
     * 模板主表 JPA 仓储（列表查询用——父类 repository 字段为泛型契约类型）
     */
    private final FreightTemplateJpaRepository jpaRepository;

    /**
     * 构造运费模板仓储。
     *
     * @param repository            模板主表 JPA 仓储
     * @param threadContext         请求级上下文（变更追踪器与快照）
     * @param convertor             模板聚合转换器
     * @param changeTrackerProvider 变更追踪器提供者
     */
    public FreightTemplateRepositoryImpl(FreightTemplateJpaRepository repository,
                                         ThreadContext threadContext,
                                         FreightTemplateConvertor convertor,
                                         ChangeTrackerProvider changeTrackerProvider) {
        super(repository, threadContext, convertor, changeTrackerProvider);
        this.jpaRepository = repository;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 聚合根主键 = 模板 ID。
     */
    @Override
    protected Long retrieveIDFromRoot(FreightTemplate root) {
        return root.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 插入根行（新模板首次落库）。
     */
    @Override
    protected void doInsert(FreightTemplate root) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 单表聚合仅有根行字段变更：整行更新主表（JPA merge——不存在时插入、
     * 已存在时字段覆盖，避免逐字段映射漂移）。变更集空判定由模板
     * save 流程完成（isEmpty 提前返回，本方法仅在存在变更时被调用）。
     */
    @Override
    protected void doUpdate(FreightTemplate root, ChangeSet changeSet) {
        repository.save(convertor.convertToPO(root));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除整模板：委托 {@link #deleteByID}（按模板 ID）。
     */
    @Override
    public int delete(FreightTemplate template) {
        return template == null ? 0 : deleteByID(template.getId());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除根行，返回真实删除条数（0/1，非契约形）。事务边界由用例层持有。
     */
    @Override
    public int deleteByID(Long templateId) {
        if (templateId == null || !repository.existsById(templateId)) {
            return 0;
        }
        repository.deleteById(templateId);
        return 1;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 列表按店铺查询（租户过滤 + shop_id 定位，新模板在前）；
     * 返回对象未登记变更追踪（仅供读取，修改后不可直接 save——调用方
     * 如需更新须经 getByID 重新加载建立快照基线）。
     */
    @Override
    public List<FreightTemplate> listByShopId(Long shopId) {
        return jpaRepository.findByShopIdOrderByIdDesc(shopId).stream()
                .map(po -> convertor.convertToRoot(po, null))
                .toList();
    }
}