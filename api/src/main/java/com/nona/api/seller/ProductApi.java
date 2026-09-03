package com.nona.api.seller;

import com.nona.api.HttpResponse;
import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;

import java.util.List;

/**
 * 商家端商品草稿契约（/seller/products…，SELLER 角色）：草稿 CRUD、
 * 图片引用管理（增删/设主图）与自定义属性键值管理（增删改）。
 * <p>
 * 当前店铺由认证上下文定位（登录时账号-店铺关联写入用户上下文，过滤器填充
 * 请求租户=当前店铺 ID）；商品归属当前店铺，跨店铺商品访问按不存在呈现
 * （404，不泄露归属，fail-closed）。草稿可保存不生效（状态恒 DRAFT，
 * 一期无发布/审核链路）；类目/品牌引用非空时目标必须存在且启用。
 * 图片只管理 URL 引用（上传走 {@code POST /files}），删除引用
 * 不删除文件。
 *
 * @author nona9961
 */
public interface ProductApi {

    /**
     * 创建商品草稿（名称必填；描述/类目/品牌可空）。
     *
     * @param request 草稿主体
     * @return 新建草稿详情（状态 DRAFT）
     */
    HttpResponse<ProductDetail> createDraft(ProductDraftRequest request);

    /**
     * 草稿列表（翻页；新商品在前）。
     *
     * @param query 分页参数（pageNum/pageSize）
     * @return 分页草稿列表
     */
    HttpResponse<PageResult<ProductDraftItem>> listDrafts(PageQuery query);

    /**
     * 草稿详情（含图片与属性完整列表）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 草稿详情
     */
    HttpResponse<ProductDetail> getProduct(Long productId);

    /**
     * 更新草稿主体：名称/描述/类目/品牌整体替换（引用变更时校验新目标
     * 存在且启用；保留不变的历史归属合法）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   新主体
     * @return 更新后的草稿详情
     */
    HttpResponse<ProductDetail> updateProduct(Long productId, ProductDraftRequest request);

    /**
     * 删除草稿（物理删除：级联删除图片/属性引用行；删除引用不删除文件）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> deleteProduct(Long productId);

    /**
     * 添加图片引用（主图至多一条，显式 primary=true 时替换现有主图）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   图片 URL 与主图标记
     * @return 新建图片条目（含分配的 ID 与主图标记）
     */
    HttpResponse<ProductImageItem> addImage(Long productId, ProductImageRequest request);

    /**
     * 删除图片引用（主图被删后主图位清空，其余图片标记不变）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param imageId   图片引用 ID（必须属于当前商品，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> removeImage(Long productId, Long imageId);

    /**
     * 设置主图（清除原主图标记，目标图片设为新主图）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param imageId   图片引用 ID（必须属于当前商品，否则 404）
     * @return 更新后的主图条目
     */
    HttpResponse<ProductImageItem> setPrimaryImage(Long productId, Long imageId);

    /**
     * 添加自定义属性（键必填且同商品内唯一）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   属性键值
     * @return 新建属性条目
     */
    HttpResponse<ProductAttributeItem> addAttribute(Long productId, ProductAttributeRequest request);

    /**
     * 更新自定义属性（改键需与其余属性保持唯一；值可空）。
     *
     * @param productId   商品 ID（必须属于当前店铺，否则 404）
     * @param attributeId 属性 ID（必须属于当前商品，否则 404）
     * @param request     新键值
     * @return 更新后的属性条目
     */
    HttpResponse<ProductAttributeItem> updateAttribute(Long productId, Long attributeId,
                                                       ProductAttributeRequest request);

    /**
     * 删除自定义属性。
     *
     * @param productId   商品 ID（必须属于当前店铺，否则 404）
     * @param attributeId 属性 ID（必须属于当前商品，否则 404）
     * @return 成功响应
     */
    HttpResponse<Void> removeAttribute(Long productId, Long attributeId);

    /**
     * 整体替换规格模板并重建 SKU 集（规格模板是 SKU 集的唯一生成依据）：
     * 同组合（specHash 相同）保留既有 SKU 的价格/启用/身份（skuId 不变，
     * 跨域引用稳定），新增组合生成新 SKU（默认未定价、停用），消失组合
     * 的 SKU 移除；空 dimensions（null 或空列表）= 空模板，清空 SKU 集；
     * 同一模板重复配置幂等。组合数超上限拒绝且模板保持原值。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   新规格模板（整体替换；空 dimensions=清空 SKU 集）
     * @return 重建后的 SKU 集（按模板展开序）
     */
    HttpResponse<List<SkuItem>> configureSpecTemplate(Long productId,
                                                      SpecTemplateRequest request);

    /**
     * 更新 SKU 价格（价格语义：null=清除价格复位未定价——草稿期合法；
     * 非空必须为正整数分，0 与负数拒绝）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param skuId     SKU ID（必须属于当前商品，否则 404）
     * @param request   新价格（价格 null=清除定价）
     * @return 更新后的 SKU 条目
     */
    HttpResponse<SkuItem> updateSkuPrice(Long productId, Long skuId,
                                         SkuPriceRequest request);

    /**
     * 切换 SKU 启用状态（停用 SKU 不出售；新生成的 SKU 默认停用——
     * 显式启用避免未配价误售）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param skuId     SKU ID（必须属于当前商品，否则 404）
     * @param request   启用状态
     * @return 更新后的 SKU 条目
     */
    HttpResponse<SkuItem> setSkuEnabled(Long productId, Long skuId,
                                        SkuEnabledRequest request);
}