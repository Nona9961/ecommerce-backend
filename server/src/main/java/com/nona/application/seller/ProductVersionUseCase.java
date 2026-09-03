package com.nona.application.seller;

import com.nona.api.common.PageQuery;
import com.nona.api.common.PageResult;
import com.nona.api.seller.ProductAttributeItem;
import com.nona.api.seller.ProductDetail;
import com.nona.api.seller.ProductImageItem;
import com.nona.api.seller.ProductVersionItem;
import com.nona.domain.catalog.entity.Product;
import com.nona.domain.catalog.entity.ProductAttribute;
import com.nona.domain.catalog.entity.ProductContent;
import com.nona.domain.catalog.entity.ProductEditVersion;
import com.nona.domain.catalog.entity.ProductImage;
import com.nona.domain.catalog.factory.ProductEditVersionFactory;
import com.nona.domain.catalog.repo.ProductEditVersionRepository;
import com.nona.domain.catalog.repo.ProductRepository;
import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.ThreadContext;
import com.nona.inf.persistence.converters.ProductSnapshotConvertor;
import com.nona.inf.persistence.converters.ProductSnapshotJson;
import com.nona.util.JacksonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 商家端商品编辑版本用例：保存留痕（每次编辑落库生成版本行）、版本历史
 * 查询（按商品分页，新版本在前）与回滚（以历史快照为新版本内容重走保存
 * 流程，生成 ROLLBACK 新版本行）编排。
 * <p>
 * 事务边界：留痕（版本行插入）与回滚（内容重置 + 聚合保存 + ROLLBACK
 * 版本行插入）均为用例事务内完成——版本链写入与聚合保存同事务
 * （all-or-nothing：内容变更与留痕不可分割）；历史查询为读路径。
 * 操作人取认证上下文身份标识（ThreadContext.identity，认证过滤器写入，
 * 不来自请求体）；当前店铺由租户过滤定位（跨店铺商品与版本行在数据访问
 * 层即被拦截，按不存在呈现，fail-closed）。版本号分配：同商品 MAX+1 +
 * DB 唯一约束 (product_id, version_no) 兜底并发冲突。
 * <p>
 * 保存触发点（每次保存生成版本）：商品各变更面用例
 * （ProductUseCase 写路径）在聚合保存成功后调用本用例 {@link #recordEdit}
 * ——创建基线与每次内容修改均留痕，无变更保存（变更集为空）不产生版本行
 * （避免无意义留痕）。快照序列化经 {@code ProductSnapshotConvertor}
 * （聚合全部内容全量 JSON），轻量摘要（列表项）派生自快照。
 *
 * @author nona9961
 */
@Service
public class ProductVersionUseCase {

    /**
     * 商品仓储（回滚路径聚合加载与保存）
     */
    private final ProductRepository productRepository;

    /**
     * 编辑版本仓储（append-only 版本行）
     */
    private final ProductEditVersionRepository editVersionRepository;

    /**
     * 编辑版本工厂（版本行创建：ID 生成 + 触发类型定型）
     */
    private final ProductEditVersionFactory editVersionFactory;

    /**
     * 请求上下文（操作人身份标识取值）
     */
    private final ThreadContext threadContext;

    /**
     * 商品内容快照转换器（快照序列化/反序列化）
     */
    private final ProductSnapshotConvertor snapshotConvertor;

    /**
     * 构造商品编辑版本用例。
     *
     * @param productRepository   商品仓储
     * @param editVersionRepository 编辑版本仓储
     * @param editVersionFactory  编辑版本工厂
     * @param threadContext       请求上下文（操作人）
     * @param snapshotConvertor   商品内容快照转换器（序列化/反序列化）
     */
    public ProductVersionUseCase(ProductRepository productRepository,
                                 ProductEditVersionRepository editVersionRepository,
                                 ProductEditVersionFactory editVersionFactory,
                                 ThreadContext threadContext,
                                 ProductSnapshotConvertor snapshotConvertor) {
        this.productRepository = productRepository;
        this.editVersionRepository = editVersionRepository;
        this.editVersionFactory = editVersionFactory;
        this.threadContext = threadContext;
        this.snapshotConvertor = snapshotConvertor;
    }

    /**
     * 保存留痕：为一次已落库的编辑生成 EDIT 版本行——快照序列化（当前
     * 聚合全部内容）→ 版本号 MAX+1 分配 → 版本行插入（与聚合保存同
     * 事务，由调用方事务承载）。
     * <p>
     * 操作人取认证上下文身份标识：非认证上下文（身份缺失）不产生留痕
     * ——真实请求路径身份恒有值（认证过滤器写入），缺失分支为防御性
     * 跳过（与「无变更保存不插行」同属版本链例外模式，避免无名操作人
     * 行污染审计语义，也不阻断商品主体写路径）。
     *
     * @param product 已保存的商品聚合（当前内容）
     */
    @Transactional
    public void recordEdit(Product product) {
        final String identity = currentOperator();
        if (identity == null || identity.isBlank()) {
            return;
        }
        final String snapshotJson = snapshotConvertor.toSnapshotJson(product);
        final int versionNo = editVersionRepository.maxVersionNo(product.getId()) + 1;
        editVersionRepository.append(editVersionFactory.createEdit(
                product.getId(), versionNo, snapshotJson, identity));
    }

    /**
     * 版本历史查询（按商品分页，新版本在前）：版本号/触发类型/操作人/
     * 产生时间/内容摘要（摘要派生自快照，列表不承载完整快照）。
     * 版本行与商品同租户（tenant=shopId，读路径由租户过滤定位行集），
     * 跨店铺商品版本行按不存在呈现（fail-closed，空列表）。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param query     分页参数（pageNum/pageSize 已归一化）
     * @return 分页版本列表（新版本在前）
     */
    public PageResult<ProductVersionItem> history(Long productId, PageQuery query) {
        final List<ProductEditVersion> versions = editVersionRepository.listByProductPaged(
                productId, Math.toIntExact(query.offset()), query.pageSize());
        final long total = editVersionRepository.countByProduct(productId);
        return PageResult.of(versions.stream().map(ProductVersionUseCase::toItem).toList(), total, query);
    }

    /**
     * 回滚到指定版本：目标版本快照 → 内容载体重建 → 聚合整体重置
     * （restoreContent，不变量由聚合守卫）→ 聚合保存 → ROLLBACK 版本行
     * 插入（版本号递增）——回滚生成新版本，历史版本行只增不改。目标
     * 版本不存在/不属于当前商品按不存在呈现（404）；版本号非正数
     * 拒绝（400）。
     * <p>
     * 回滚流程（加载 → 重置 → 保存 → 新版本行）同属本方法事务：任一
     * 环节失败整体回滚——内容变更与留痕不可分割。
     *
     * @param productId 商品 ID（必须属于当前店铺，否则 404）
     * @param versionNo 目标版本号（正整数）
     * @return 回滚后的草稿详情（内容 = 目标版本内容）
     */
    @Transactional
    public ProductDetail rollback(Long productId, int versionNo) {
        if (versionNo <= 0) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_INVALID.code(), "版本号必须为正整数");
        }
        final Product product = requireProduct(productId);
        final ProductEditVersion target =
                editVersionRepository.getByProductAndVersion(productId, versionNo);
        if (target == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_VERSION_NOT_FOUND.code(), "版本不存在");
        }
        final ProductContent content = snapshotConvertor.toContent(target.getSnapshotJson());
        product.restoreContent(content);
        productRepository.save(product);
        final String snapshotJson = snapshotConvertor.toSnapshotJson(product);
        final int nextVersionNo = editVersionRepository.maxVersionNo(productId) + 1;
        editVersionRepository.append(editVersionFactory.createRollback(
                productId, nextVersionNo, snapshotJson, currentOperator()));
        return toDetail(product);
    }

    /**
     * 版本行 → 历史列表项（摘要由快照派生：商品名称 + 图片/属性/SKU 计数）。
     *
     * @param version 版本行
     * @return 历史列表项
     */
    private static ProductVersionItem toItem(ProductEditVersion version) {
        return new ProductVersionItem(
                version.getVersionNo(),
                version.getTriggerType().name(),
                version.getOperator(),
                formatCreatedAt(version.getCreatedAt()),
                deriveSummary(version.getSnapshotJson()));
    }

    /**
     * 产生时间 ISO 8601 文本化（LocalDateTime 无时区语义）。
     *
     * @param createdAt 产生时间（读回路径恒非空；防御 null）
     * @return ISO 8601 文本；null 返回空串
     */
    private static String formatCreatedAt(java.time.LocalDateTime createdAt) {
        return createdAt == null ? "" : createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 内容摘要派生（快照 → 商品名称 + 计数；列表不承载完整快照）。
     *
     * @param snapshotJson 版本快照 JSON
     * @return 摘要文本（名称 + 图片/属性/SKU 计数）
     */
    private static String deriveSummary(String snapshotJson) {
        final ProductSnapshotJson snapshot =
                JacksonUtil.fromJsonString(snapshotJson, ProductSnapshotJson.class);
        if (snapshot == null || snapshot.name() == null) {
            return "";
        }
        return snapshot.name() + "（图片" + snapshot.images().size()
                + "/属性" + snapshot.attributes().size()
                + "/SKU" + snapshot.skus().size() + "）";
    }

    /**
     * 领域商品 → 契约详情（回滚返回值）。
     *
     * @param product 商品聚合
     * @return 契约详情
     */
    private static ProductDetail toDetail(Product product) {
        return new ProductDetail(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getBrandId(),
                product.getStatus().name(),
                product.imagesOrdered().stream().map(ProductVersionUseCase::toImageItem).toList(),
                product.attributesOrdered().stream().map(ProductVersionUseCase::toAttributeItem).toList());
    }

    /**
     * 领域图片引用 → 契约条目。
     *
     * @param image 图片引用
     * @return 契约条目
     */
    private static ProductImageItem toImageItem(ProductImage image) {
        return new ProductImageItem(image.getId(), image.getUrl(), image.isPrimary());
    }

    /**
     * 领域属性 → 契约条目。
     *
     * @param attribute 属性
     * @return 契约条目
     */
    private static ProductAttributeItem toAttributeItem(ProductAttribute attribute) {
        return new ProductAttributeItem(attribute.getId(), attribute.getKey(), attribute.getValue());
    }

    /**
     * 加载商品并断言存在（租户过滤 fail-closed：跨店铺商品按不存在呈现）。
     *
     * @param productId 商品 ID
     * @return 商品聚合
     */
    private Product requireProduct(Long productId) {
        final Product product = productRepository.getByID(productId);
        if (product == null) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_NOT_FOUND.code(), "商品不存在");
        }
        return product;
    }

    /**
     * 当前操作人（认证上下文身份标识；非认证请求上下文返回 null）。
     *
     * @return 操作人身份标识；缺失返回 null
     */
    private String currentOperator() {
        return threadContext.getIdentity();
    }
}