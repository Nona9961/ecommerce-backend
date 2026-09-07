package com.nona.inf.persistence.repository;

import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.inf.persistence.converters.FreightTemplateConvertor;
import com.nona.inf.persistence.repository.jpa.FreightTemplateJpaRepository;
import com.nona.inf.persistence.tracking.ChangeTrackerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 默认运费模板仓储定位契约测试：findDefaultByShopId 按店铺定位默认
 * 模板行（is_default=true；租户过滤 fail-closed）——回退锚点查询通道。
 * <p>
 * 红阶段：findDefaultByShopId 实现为签名冻结（UnsupportedOperationException）
 * ——本用例红于实现缺失；绿阶段实现（JPA 派生查询 + 转换器透传）后
 * 转绿。
 */
class FreightTemplateRepositoryDefaultContractTest {

    /**
     * 定位契约：按店铺 ID 查询返回默认模板（非空——回退定位结果的
     * 存在性契约；缺失语义（null）由消费侧防御拒绝承载）。
     */
    @Test
    @DisplayName("按店铺定位默认模板非空")
    void findDefaultByShopId_returnsDefaultTemplate() {
        final FreightTemplateRepositoryImpl impl = new FreightTemplateRepositoryImpl(
                mock(FreightTemplateJpaRepository.class),
                mock(FreightTemplateConvertor.class),
                mock(ChangeTrackerProvider.class));

        final FreightTemplate defaultTemplate = impl.findDefaultByShopId(9001L);

        assertThat(defaultTemplate).isNotNull();
    }
}