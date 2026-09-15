package com.nona.application.seller;

import com.nona.api.seller.FreightTemplateDetail;
import com.nona.domain.catalog.entity.FreightRuleType;
import com.nona.domain.catalog.entity.FreightTemplate;
import com.nona.domain.catalog.entity.FreightTemplateStatus;
import com.nona.domain.catalog.factory.FreightTemplateFactory;
import com.nona.domain.catalog.repo.FreightTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 模板列表/详情读契约回归（既有 CRUD 读通道按非空契约回归）：默认模板与
 * 普通模板同构——列表/详情读通道不区分模板身份，开店后默认模板行经
 * 既有读路径正常呈现（恒非空契约的读面落点）。
 * <p>
 * 契约锚点：list/detail 为既有实现（未触碰），本文件用例应保持
 * 绿色——证明读契约与模板身份正交、既有 CRUD 读面零破坏。
 */
@ExtendWith(MockitoExtension.class)
class FreightTemplateListDetailReadContractUnitTest {

    /**
     * 测试店铺 ID
     */
    private static final long SHOP_ID = 1001L;

    /**
     * 模板 ID（8 参构造——普通行形态；默认行同表同构，读通道等价）
     */
    private static final long TEMPLATE_ID = 80011L;

    @Mock
    private FreightTemplateRepository freightTemplateRepository;

    @Mock
    private FreightTemplateFactory freightTemplateFactory;

    private FreightTemplateUseCase useCase;

    /**
     * 装配用例（读通道为既有实现；模板行桩在各自用例内就位）。
     */
    @BeforeEach
    void setUp() {
        useCase = new FreightTemplateUseCase(freightTemplateRepository, freightTemplateFactory);
    }

    /**
     * 模板行桩（8 参构造器——既有创建路径，行形态与默认模板同构：
     * freight_template 主表一行、归属店铺、FREE 规则）。
     *
     * @return 模板行
     */
    private static FreightTemplate templateRow() {
        return new FreightTemplate(TEMPLATE_ID, SHOP_ID,
                "默认运费模板", FreightRuleType.FREE, null, null, null,
                FreightTemplateStatus.ENABLED);
    }

    /**
     * happy：列表读包含模板行（默认模板存在下列表非空且概要形态完整）。
     */
    @Test
    @DisplayName("列表读返回模板行（含默认模板同构行）")
    void list_includesTemplateRows() {
        when(freightTemplateRepository.listByShopId(SHOP_ID)).thenReturn(List.of(templateRow()));

        final List<FreightTemplateDetail> list = useCase.list(SHOP_ID);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(TEMPLATE_ID);
        assertThat(list.get(0).shopId()).isEqualTo(SHOP_ID);
        assertThat(list.get(0).name()).isEqualTo("默认运费模板");
        assertThat(list.get(0).ruleType()).isEqualTo("FREE");
        assertThat(list.get(0).status()).isEqualTo("ENABLED");
    }

    /**
     * happy：详情读返回模板行（默认模板可读——商家端模板列表/详情
     * 包含开店自动建成的默认模板）。
     */
    @Test
    @DisplayName("详情读返回模板行")
    void detail_readsTemplateRow() {
        when(freightTemplateRepository.getByID(TEMPLATE_ID)).thenReturn(templateRow());

        final FreightTemplateDetail detail = useCase.detail(TEMPLATE_ID);

        assertThat(detail.id()).isEqualTo(TEMPLATE_ID);
        assertThat(detail.name()).isEqualTo("默认运费模板");
        assertThat(detail.ruleType()).isEqualTo("FREE");
        assertThat(detail.status()).isEqualTo("ENABLED");
    }
}