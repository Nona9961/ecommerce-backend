package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.inf.context.TenantPrivilege;
import com.nona.inf.persistence.converters.FreightTemplateConvertor;
import com.nona.inf.persistence.po.catalog.FreightTemplatePO;
import com.nona.inf.persistence.repository.jpa.FreightTemplateJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认运费模板仓储定位契约测试：findDefaultByShopId 按店铺定位默认
 * 模板行（is_default=true；租户过滤 fail-closed）——回退锚点查询通道。
 * <p>
 * 红阶段：findDefaultByShopId 实现为签名冻结（UnsupportedOperationException）
 * ——本用例红于实现缺失；绿阶段实现（JPA 派生查询 + 转换器透传）后
 * 转绿。
 */
class FreightTemplateRepositoryDefaultContractUnitTest {

    /**
     * 定位契约：按店铺 ID 查询返回默认模板（非空——回退定位结果的
     * 存在性契约；缺失语义（null）由消费侧防御拒绝承载）。
     */
    @Test
    @DisplayName("按店铺定位默认模板非空")
    void findDefaultByShopId_returnsDefaultTemplate() {
        final FreightTemplateJpaRepository jpaRepository = mock(FreightTemplateJpaRepository.class);
        final FreightTemplateConvertor convertor = mock(FreightTemplateConvertor.class);
        final ChangeTrackerProvider changeTrackerProvider = mock(ChangeTrackerProvider.class);
        final TenantPrivilege tenantPrivilege = mock(TenantPrivilege.class);
        when(jpaRepository.findByShopIdAndIsDefaultTrue(9001L))
                .thenReturn(Optional.of(new FreightTemplatePO()));
        when(convertor.convertToRoot(any(FreightTemplatePO.class), any()))
                .thenReturn(new FreightTemplate(6001L, 9001L, "默认运费模板",
                        FreightRuleType.FREE, null, null, null,
                        FreightTemplateStatus.ENABLED, true));
        final FreightTemplateRepositoryImpl impl = new FreightTemplateRepositoryImpl(
                jpaRepository, convertor, changeTrackerProvider, tenantPrivilege);

        final FreightTemplate defaultTemplate = impl.findDefaultByShopId(9001L);

        assertThat(defaultTemplate).isNotNull();
    }
}