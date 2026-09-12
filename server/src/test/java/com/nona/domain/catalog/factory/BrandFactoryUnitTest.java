package com.nona.domain.catalog.factory;

import com.nona.domain.catalog.entity.Brand;
import com.nona.domain.catalog.entity.BrandStatus;
import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 品牌工厂单元测试：品牌创建的入口行为。
 * <p>
 * 覆盖：happy（创建成功、ID 生成、状态定型 ENABLED、logo 透传/缺省）、
 * error（空名称拒绝——创建路径校验收敛在工厂）。
 */
class BrandFactoryUnitTest {

    /**
     * 品牌工厂
     */
    private final BrandFactory factory = new BrandFactory();

    /**
     * happy：创建成功——ID 生成、初始状态 ENABLED、logo 透传。
     */
    @Test
    @DisplayName("创建品牌成功且状态为 ENABLED")
    void create_succeedsWithEnabledStatus() {
        final Brand brand = factory.create("耐克", "logo-a.png");

        assertThat(brand.getId()).isNotNull();
        assertThat(brand.getName()).isEqualTo("耐克");
        assertThat(brand.getLogo()).isEqualTo("logo-a.png");
        assertThat(brand.getStatus()).isEqualTo(BrandStatus.ENABLED);
    }

    /**
     * happy：logo 缺省时创建成功（logo 可空）。
     */
    @Test
    @DisplayName("无 logo 创建成功")
    void create_withoutLogoSucceeds() {
        final Brand brand = factory.create("耐克", null);

        assertThat(brand.getId()).isNotNull();
        assertThat(brand.getName()).isEqualTo("耐克");
        assertThat(brand.getLogo()).isNull();
        assertThat(brand.getStatus()).isEqualTo(BrandStatus.ENABLED);
    }

    /**
     * error：名称为空拒绝创建。
     */
    @Test
    @DisplayName("空名称拒绝创建")
    void create_blankNameRejected() {
        assertThatThrownBy(() -> factory.create("  ", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("品牌名称");
    }
}