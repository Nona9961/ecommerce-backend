package com.nona.application.seller;

import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.factory.FreightTemplateFactory;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 默认运费模板冻结守卫契约测试（回退锚点恒可用）：默认模板禁删除——
 * 删除入口（商家端用例 delete）对默认模板拒绝（frozen 400）且不落
 * 删除；普通模板删除不受影响（回归锚点）。delete 行为签名
 * 冻结（实现缺失）——两类契约测试红于 UnsupportedOperationException。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightTemplateDefaultGuardUnitTest {

    /**
     * 默认模板 ID（店铺回退锚点）
     */
    private static final long DEFAULT_TEMPLATE_ID = 80001L;

    /**
     * 普通模板 ID
     */
    private static final long NORMAL_TEMPLATE_ID = 80002L;

    @Mock
    private FreightTemplateRepository freightTemplateRepository;

    @Mock
    private FreightTemplateFactory freightTemplateFactory;

    @Mock
    private FreightTemplate defaultTemplate;

    @Mock
    private FreightTemplate normalTemplate;

    private FreightTemplateUseCase useCase;

    /**
     * 装配用例与模板桩：默认模板标记 isDefault=true；普通模板 isDefault=false。
     */
    @BeforeEach
    void setUp() {
        useCase = new FreightTemplateUseCase(freightTemplateRepository, freightTemplateFactory);
        when(freightTemplateRepository.getByID(DEFAULT_TEMPLATE_ID)).thenReturn(defaultTemplate);
        when(freightTemplateRepository.getByID(NORMAL_TEMPLATE_ID)).thenReturn(normalTemplate);
        when(defaultTemplate.isDefault()).thenReturn(true);
        when(normalTemplate.isDefault()).thenReturn(false);
    }

    /**
     * fail：删除默认模板拒绝（catalog.freight_default_template_frozen 400）
     * 且不落删除——回退锚点恒存在（非空设计）。
     */
    @Test
    @DisplayName("删除默认模板拒绝且不落库")
    void delete_defaultTemplate_rejected() {
        assertThatThrownBy(() -> useCase.delete(DEFAULT_TEMPLATE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("catalog.freight_default_template_frozen");

        verify(freightTemplateRepository, never()).deleteByID(DEFAULT_TEMPLATE_ID);
    }

    /**
     * happy（回归锚点）：普通模板删除不受影响——守卫只针对默认模板
     * （默认身份由标记承载，读通道与删除语义对普通模板保持既有行为）。
     */
    @Test
    @DisplayName("普通模板删除不受影响")
    void delete_normalTemplate_deletes() {
        useCase.delete(NORMAL_TEMPLATE_ID);

        verify(freightTemplateRepository).deleteByID(NORMAL_TEMPLATE_ID);
    }
}