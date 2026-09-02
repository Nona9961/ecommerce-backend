package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 店铺聚合根单元测试：店铺信息编辑与店铺分类管理的领域行为。
 * <p>
 * 覆盖：happy（编辑生效、分类增/改/删、order 递增分配）、critical（空分类集合、
 * 删除最后一个分类、order 不因删除重排）、error（空名称拒绝、目标分类不存在拒绝、
 * 重复添加同一分类拒绝）。
 */
class ShopTest {

    /**
     * happy：编辑店铺信息后字段更新、状态不变（状态由平台侧管理，不接受商家编辑）。
     */
    @Test
    @DisplayName("编辑店铺信息生效且状态不变")
    void updateInfo_changesFieldsKeepsStatus() {
        final Shop shop = new Shop(1001L, "原店铺", "logo-a.png", "旧简介", ShopStatus.NORMAL);
        shop.updateInfo("新店铺名", "logo-b.png", "新简介");

        assertThat(shop.getName()).isEqualTo("新店铺名");
        assertThat(shop.getLogo()).isEqualTo("logo-b.png");
        assertThat(shop.getDescription()).isEqualTo("新简介");
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.NORMAL);
    }

    /**
     * happy：新增分类自动分配递增 order（首个为 1，随后 +1）。
     */
    @Test
    @DisplayName("新增分类自动分配递增 order")
    void addCategory_assignsIncrementalOrder() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        final ShopCategory first = shopCategory(1L, 1001L, "零食");
        final ShopCategory second = shopCategory(2L, 1001L, "饮料");

        shop.addCategory(first);
        shop.addCategory(second);

        assertThat(shop.categoryCount()).isEqualTo(2);
        final List<ShopCategory> sorted = shop.categoriesOrdered();
        assertThat(sorted.get(0).getName()).isEqualTo("零食");
        assertThat(sorted.get(0).getOrder()).isEqualTo(1);
        assertThat(sorted.get(1).getName()).isEqualTo("饮料");
        assertThat(sorted.get(1).getOrder()).isEqualTo(2);
    }

    /**
     * happy：分类改名后名称更新、order 不变。
     */
    @Test
    @DisplayName("分类改名后名称更新且 order 不变")
    void renameCategory_keepsOrder() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        final ShopCategory category = shopCategory(1L, 1001L, "零食");
        shop.addCategory(category);

        shop.renameCategory(1L, "休闲零食");

        assertThat(shop.getCategoryById(1L).orElseThrow().getName()).isEqualTo("休闲零食");
        assertThat(shop.getCategoryById(1L).orElseThrow().getOrder()).isEqualTo(1);
    }

    /**
     * happy：删除分类后集合移除，其余分类 order 保持（不重排）。
     */
    @Test
    @DisplayName("删除分类后其余 order 保持")
    void removeCategory_removesWithoutReorder() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        final ShopCategory first = shopCategory(1L, 1001L, "零食");
        final ShopCategory second = shopCategory(2L, 1001L, "饮料");
        shop.addCategory(first);
        shop.addCategory(second);

        shop.removeCategory(1L);

        assertThat(shop.categoryCount()).isEqualTo(1);
        assertThat(shop.getCategoryById(1L)).isEmpty();
        assertThat(shop.getCategoryById(2L).orElseThrow().getOrder()).isEqualTo(2);
    }

    /**
     * critical：空集合下新增分类 order 从 1 开始。
     */
    @Test
    @DisplayName("空集合新增分类 order 为 1")
    void addCategory_firstCategoryOrderIsOne() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        shop.addCategory(shopCategory(1L, 1001L, "零食"));
        assertThat(shop.getCategoryById(1L).orElseThrow().getOrder()).isEqualTo(1);
    }

    /**
     * critical：删除最后一个分类后集合为空，可继续新增（order 重新从 1 起）。
     */
    @Test
    @DisplayName("删除最后一个分类后可继续新增")
    void removeLastCategory_allowsAddingAgain() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        shop.addCategory(shopCategory(1L, 1001L, "零食"));
        shop.removeCategory(1L);
        shop.addCategory(shopCategory(2L, 1001L, "饮料"));

        assertThat(shop.categoryCount()).isEqualTo(1);
        assertThat(shop.getCategoryById(2L).orElseThrow().getOrder()).isEqualTo(1);
    }

    /**
     * error：编辑店铺信息时名称为空拒绝。
     */
    @Test
    @DisplayName("空店铺名称拒绝编辑")
    void updateInfo_blankNameRejected() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        assertThatThrownBy(() -> shop.updateInfo("  ", "logo.png", "简介"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺名称");
    }

    /**
     * error：新增分类名称为空拒绝。
     */
    @Test
    @DisplayName("空分类名称拒绝新增")
    void addCategory_blankNameRejected() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        final ShopCategory category = shopCategory(1L, 1001L, "  ");
        assertThatThrownBy(() -> shop.addCategory(category))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类名称");
    }

    /**
     * error：重复添加同一分类 ID 拒绝（聚合内标识唯一）。
     */
    @Test
    @DisplayName("重复添加同一分类拒绝")
    void addCategory_duplicateIdRejected() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        shop.addCategory(shopCategory(1L, 1001L, "零食"));
        assertThatThrownBy(() -> shop.addCategory(shopCategory(1L, 1001L, "重复")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    /**
     * error：改名目标分类不存在拒绝。
     */
    @Test
    @DisplayName("改名的分类不存在拒绝")
    void renameCategory_missingTargetRejected() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        assertThatThrownBy(() -> shop.renameCategory(99L, "新名字"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：改名新名称为空拒绝。
     */
    @Test
    @DisplayName("改名新名称为空拒绝")
    void renameCategory_blankNameRejected() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        shop.addCategory(shopCategory(1L, 1001L, "零食"));
        assertThatThrownBy(() -> shop.renameCategory(1L, " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分类名称");
    }

    /**
     * error：删除目标分类不存在拒绝。
     */
    @Test
    @DisplayName("删除的分类不存在拒绝")
    void removeCategory_missingTargetRejected() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        assertThatThrownBy(() -> shop.removeCategory(99L))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * happy：分类只读快照不暴露内部可变集合。
     */
    @Test
    @DisplayName("分类快照为不可变副本")
    void categoriesOrdered_returnsImmutableCopy() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        shop.addCategory(shopCategory(1L, 1001L, "零食"));
        final List<ShopCategory> snapshot = shop.categoriesOrdered();
        assertThatThrownBy(() -> snapshot.add(shopCategory(2L, 1001L, "饮料")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(shop.categoryCount()).isEqualTo(1);
    }

    /**
     * happy：按 ID 取分类返回 Optional 形态。
     */
    @Test
    @DisplayName("按 ID 查询分类")
    void getCategoryById_returnsOptional() {
        final Shop shop = new Shop(1001L, "店铺", null, null, ShopStatus.NORMAL);
        shop.addCategory(shopCategory(1L, 1001L, "零食"));
        final Optional<ShopCategory> found = shop.getCategoryById(1L);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getShopId()).isEqualTo(1001L);
    }

    /**
     * 构造分类实体（测试数据）。
     *
     * @param id     分类 ID
     * @param shopId 归属店铺 ID
     * @param name   分类名称
     * @return 分类实体
     */
    private static ShopCategory shopCategory(Long id, Long shopId, String name) {
        return new ShopCategory(id, shopId, name);
    }
}