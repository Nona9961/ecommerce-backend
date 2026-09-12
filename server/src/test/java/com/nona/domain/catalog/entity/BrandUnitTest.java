package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 品牌聚合根单元测试：品牌改名/logo 更新/状态迁移的领域行为。
 * <p>
 * 覆盖：happy（改名生效、logo 更新/清除、禁用/启用迁移）、critical（重复禁用幂等、
 * 重复启用幂等、logo 从无到有）、error（空名称拒绝改名）。
 * 名称唯一性为仓储/用例层守卫（跨行不变量），不在本聚合内验证。
 */
class BrandUnitTest {

    /**
     * happy：改名后名称更新，logo 与状态不变。
     */
    @Test
    @DisplayName("改名生效且 logo 状态不变")
    void rename_updatesNameKeepsLogoAndStatus() {
        final Brand brand = new Brand(1001L, "Nike", "logo-a.png", BrandStatus.ENABLED);
        brand.rename("耐克");

        assertThat(brand.getName()).isEqualTo("耐克");
        assertThat(brand.getLogo()).isEqualTo("logo-a.png");
        assertThat(brand.getStatus()).isEqualTo(BrandStatus.ENABLED);
    }

    /**
     * happy：更新 logo 后生效，名称状态不变。
     */
    @Test
    @DisplayName("更新 logo 生效")
    void updateLogo_changesLogo() {
        final Brand brand = new Brand(1001L, "耐克", "logo-a.png", BrandStatus.ENABLED);
        brand.updateLogo("logo-b.png");

        assertThat(brand.getLogo()).isEqualTo("logo-b.png");
        assertThat(brand.getName()).isEqualTo("耐克");
        assertThat(brand.getStatus()).isEqualTo(BrandStatus.ENABLED);
    }

    /**
     * happy：logo 传 null 清除（logo 可空字段）。
     */
    @Test
    @DisplayName("null logo 清除")
    void updateLogo_nullClears() {
        final Brand brand = new Brand(1001L, "耐克", "logo-a.png", BrandStatus.ENABLED);
        brand.updateLogo(null);

        assertThat(brand.getLogo()).isNull();
    }

    /**
     * happy：禁用迁移至 DISABLED（行保留，软删语义）。
     */
    @Test
    @DisplayName("禁用迁移至 DISABLED")
    void disable_movesToDisabled() {
        final Brand brand = new Brand(1001L, "耐克", null, BrandStatus.ENABLED);
        brand.disable();

        assertThat(brand.getStatus()).isEqualTo(BrandStatus.DISABLED);
    }

    /**
     * critical：重复禁用幂等（已禁用再禁用保持 DISABLED）。
     */
    @Test
    @DisplayName("重复禁用幂等")
    void disable_twiceIdempotent() {
        final Brand brand = new Brand(1001L, "耐克", null, BrandStatus.DISABLED);
        brand.disable();
        brand.disable();

        assertThat(brand.getStatus()).isEqualTo(BrandStatus.DISABLED);
    }

    /**
     * happy：启用迁移至 ENABLED（禁用态恢复）。
     */
    @Test
    @DisplayName("启用迁移至 ENABLED")
    void enable_movesToEnabled() {
        final Brand brand = new Brand(1001L, "耐克", null, BrandStatus.DISABLED);
        brand.enable();

        assertThat(brand.getStatus()).isEqualTo(BrandStatus.ENABLED);
    }

    /**
     * critical：重复启用幂等（已启用再启用保持 ENABLED）。
     */
    @Test
    @DisplayName("重复启用幂等")
    void enable_twiceIdempotent() {
        final Brand brand = new Brand(1001L, "耐克", null, BrandStatus.ENABLED);
        brand.enable();
        brand.enable();

        assertThat(brand.getStatus()).isEqualTo(BrandStatus.ENABLED);
    }

    /**
     * error：改名为空拒绝（不变量收敛在聚合方法内）。
     */
    @Test
    @DisplayName("空名称拒绝改名")
    void rename_blankNameRejected() {
        final Brand brand = new Brand(1001L, "耐克", null, BrandStatus.ENABLED);
        assertThatThrownBy(() -> brand.rename("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("品牌名称");
        assertThat(brand.getName()).isEqualTo("耐克");
    }
}