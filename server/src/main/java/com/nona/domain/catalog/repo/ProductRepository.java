package com.nona.domain.catalog.repo;

import com.nona.domain.catalog.entity.EditSensitivity;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductStatus;
import com.nona.persistence.BaseRepository;

import java.util.List;

/**
 * 商品仓储接口（Product 聚合根持久化契约，实现在基础设施层）。
 * <p>
 * 富状态聚合仓储：经脚手架 DifferRepository 变更追踪（主表 product +
 * 从表 product_image / product_attribute 集合），保存由变更集驱动落库；
 * 从表行经租户过滤加载（fail-closed，跨店铺商品行不可见）。删除为
 * 物理删除（级联删从表行 + 主表行，草稿删除语义）。引用存在性查询
 * （类目/品牌）供平台侧禁用守卫跨租户使用（管理员视角全局检查，
 * 调用方需显式读放行）。
 *
 * @author nona9961
 */
public interface ProductRepository extends BaseRepository<Long, Product> {

    /**
     * 按店铺分页列出商品草稿（新商品在前——ID 倒序）。
     *
     * @param shopId 店铺 ID（与租户过滤共同定位行集）
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 商品列表；无数据为空列表
     */
    List<Product> listByShopPaged(Long shopId, int offset, int limit);

    /**
     * 按店铺统计商品草稿数（分页 total 用）。
     *
     * @param shopId 店铺 ID
     * @return 商品数
     */
    long countByShop(Long shopId);

    /**
     * 是否存在引用指定平台类目的商品（禁用守卫用；跨租户全局检查，
     * 调用方需读放行）。
     *
     * @param categoryId 平台类目 ID
     * @return 存在引用返回 true
     */
    boolean existsByCategoryId(Long categoryId);

    /**
     * 是否存在引用指定品牌的商品（禁用守卫用；跨租户全局检查，
     * 调用方需读放行）。
     *
     * @param brandId 品牌 ID
     * @return 存在引用返回 true
     */
    boolean existsByBrandId(Long brandId);

    /**
     * 按商品状态分页列出商品（平台审核列表用；管理员视角跨店铺全集，
     * 调用方需读放行——查询放行职责在应用层用例）。行集按创建序
     * （先创建的先审）。
     *
     * @param status 商品状态过滤
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 商品列表（按创建序）；无数据为空列表
     */
    List<Product> listByStatusPaged(ProductStatus status, int offset, int limit);

    /**
     * 按商品状态统计商品数（分页 total 用；管理员视角跨店铺全集，
     * 调用方需读放行）。
     *
     * @param status 商品状态
     * @return 商品数
     */
    long countByStatus(ProductStatus status);

    /**
     * 全量分页列出商品（平台商品列表无状态过滤路径；管理员视角跨店铺
     * 全集，调用方需读放行）。行集按创建序（先创建的先审）。
     *
     * @param offset 首条偏移量（从 0 开始）
     * @param limit  每页条数
     * @return 商品列表（按创建序）；无数据为空列表
     */
    List<Product> listAllPaged(int offset, int limit);

    /**
     * 全量统计商品数（分页 total 用；管理员视角跨店铺全集，调用方需
     * 读放行）。
     *
     * @return 商品数
     */
    long countAll();

    /**
     * 汇总一次待保存编辑的变更敏感性（字段级审核分流判定输入）：投影
     * 当前变更追踪器变更集 → 变更路径/增删集合 → {@link EditSensitivity}
     * 判定结果。调用方（应用层用例）在领域操作完成后、保存前调用——
     * 变更集为「当前聚合 vs 加载快照」的差异，判定为纯函数（领域常量
     * 配置，见 Product 判定契约）。
     *
     * @param product 已发生领域操作的聚合（其加载快照已登记追踪）
     * @return 编辑方向判定结果（NONE=无变更 / DISPLAY_ONLY=直改免审 /
     *         SENSITIVE=转待审核）
     */
    EditSensitivity summarizeSensitiveEdit(Product product);

    /**
     * 按 SKU ID 反查归属商品 ID（售罄事件消费定位用：SelloutEvent 只
     * 携带 skuId，消费方经本查询定位 SKU 所属商品后再加载聚合）。
     * 跨店铺语义：事件消费上下文无请求租户时需读放行/提权（放行职责在
     * 应用层用例）；SKU 不存在或不可见返回 null（不抛异常）。
     *
     * @param skuId SKU ID
     * @return 归属商品 ID；SKU 不存在返回 null
     */
    Long findProductIdBySkuId(Long skuId);

    /**
     * 是否存在商品绑定指定店铺分类（分类删除守卫用：删除店铺分类前须
     * 零商品引用——租户过滤内同店查询，调用方为当前店铺上下文）。
     *
     * @param shopCategoryId 店铺分类 ID
     * @return 存在引用返回 true
     */
    boolean existsProductBoundToShopCategory(Long shopCategoryId);

    /**
     * 按店铺分类分页列出绑定的商品（S7.1 ②「按店铺分类可筛选商品」：
     * 绑定行反查本店商品列表，每行装配完整聚合）。行集按创建序。
     *
     * @param shopCategoryId 店铺分类 ID
     * @param offset         首条偏移量（从 0 开始）
     * @param limit          每页条数
     * @return 商品列表；无数据为空列表
     */
    List<Product> listByShopCategoryPaged(Long shopCategoryId, int offset, int limit);

    /**
     * 按店铺分类统计绑定商品数（分页 total 用）。
     *
     * @param shopCategoryId 店铺分类 ID
     * @return 商品数
     */
    long countByShopCategory(Long shopCategoryId);
}