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
 * （404，不泄露归属，fail-closed）。草稿可保存不生效（状态 DRAFT，
 * 提交审核后转待审核）；类目/品牌引用非空时目标必须存在且启用。
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
     * 仅草稿可删除：非草稿状态（含待审中）删除拒绝（完整性语义：审核/在售
     * 生命周期无删除端点，后续阶段另行定义）。
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

    /**
     * 版本历史查询（按商品分页，新版本在前）：版本号/触发类型/操作人/
     * 产生时间/内容摘要（每次保存与回滚均生成版本行——编辑留痕）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param query     分页参数（pageNum/pageSize 已归一化）
     * @return 分页版本列表（新版本在前）
     */
    HttpResponse<PageResult<ProductVersionItem>> listVersions(Long productId, PageQuery query);

    /**
     * 回滚到指定版本：聚合内容重置为历史快照内容，再走保存流程生成新
     * 版本行（trigger_type=ROLLBACK，版本号递增）——撤销误改的合法路径
     * （历史版本行 append-only 只增不改）。目标版本不存在/不属于当前
     * 商品按不存在呈现（404）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param versionNo 目标版本号（正整数）
     * @return 回滚后的草稿详情（内容 = 目标版本内容）
     */
    HttpResponse<ProductDetail> rollbackProduct(Long productId, Integer versionNo);

    /**
     * 提交上架：草稿转待审核（完整性校验：规格模板非空且至少一个启用
     * SKU、全部 SKU 已定价、有主图、已挂平台类目与品牌——任一不满足拒绝）。
     * 提交后内容冻结：审核期内不再允许编辑，审核通过转在售、驳回回草稿
     * （可修改重提）；待审核状态商品买家不可见。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 提交后的商品详情（状态 PENDING_REVIEW）
     */
    HttpResponse<ProductDetail> submitForReview(Long productId);

    /**
     * 手动下架：在售 → 已下架（下架后买家不可见）。仅 ON_SALE
     * 可下架：草稿/待审/已下架态下架拒绝（400 非法状态）。下架为生命
     * 周期迁移，不产生编辑版本行。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 下架后的商品详情（状态 DELISTED）
     */
    HttpResponse<ProductDetail> delist(Long productId);

    /**
     * 手动重新上架：已下架 → 在售（状态机「在售 ⇄ 已下架」回迁——内容
     * 下架期冻结，重新上架免重审直回在售；售罄自动下架后的补货重上走
     * 本端点）。仅 DELISTED 可上架（其余状态 400 非法状态）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 上架后的商品详情（状态 ON_SALE）
     */
    HttpResponse<ProductDetail> relist(Long productId);

    /**
     * 整体替换商品店铺分类绑定集（商品侧多对多：一个商品可属多个
     * 店铺分类；编辑页多选勾选保存 = 整体替换，空集合=清空全部绑定）。
     * 绑定目标必须属于商品所属店铺（跨店铺分类 404）；待审核期/已下架态
     * 绑定拒绝（写面冻结）。分类变更属展示类编辑（敏感字段集不含店铺
     * 分类），在售态直改免审。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   目标分类集合（整体替换语义）
     * @return 绑定后的店铺分类条目列表（含名称，按绑定序）
     */
    HttpResponse<List<ShopCategoryItem>> updateShopCategories(Long productId,
                                                              ShopCategoryBindRequest request);

    /**
     * 商品店铺分类绑定回显（编辑页多选勾选初始值）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @return 已绑定店铺分类条目列表（含名称，按绑定序）；无绑定为空列表
     */
    HttpResponse<List<ShopCategoryItem>> listShopCategories(Long productId);

    /**
     * 绑定/解绑商品运费模板（模板必须存在且属于商品所属店铺，
     * 跨店铺模板 404；停用模板允许绑定）。待审核期/已下架态绑定拒绝
     * （写面冻结）。模板绑定属运营配置，在售态直改免审。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param request   目标模板 ID（null=解绑）
     * @return 成功响应
     */
    HttpResponse<Void> bindFreightTemplate(Long productId,
                                           FreightTemplateBindRequest request);
}
