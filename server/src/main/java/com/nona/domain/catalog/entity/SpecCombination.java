package com.nona.domain.catalog.entity;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 规格组合（值对象，不可变，由 {@link SpecTemplate#combinations()} 展开
 * 产出）：一个具体可售变体的规格构成——各维度的取值对集合，无独立身份。
 * <p>
 * 派生值契约：
 * <ul>
 *     <li>{@code specSummary}——按模板配置序拼接的可读文本
 *         （{@code 维度:值,维度:值}），供买家详情与订单快照展示；
 *         仅承载展示语义，不参与唯一性判定；</li>
 *     <li>{@code specHash}——规范化摘要（SHA-256 hex），唯一性判定与
 *         跨模板版本组合匹配的键：与维度配置顺序无关（维度名排序后
 *         规范化），维度调序不改变组合身份；定长 64 字符，适配
 *         product_sku 唯一约束列。</li>
 * </ul>
 * 组合内维度名必须互异（父模板已保证维度名唯一）；同一组合的取值对
 * 保持父模板展开序。
 *
 * @author nona9961
 */
public class SpecCombination {

    /**
     * SHA-256 摘要算法名
     */
    private static final String SHA_256 = "SHA-256";

    /**
     * 取值对列表（保持父模板展开序，不可变）
     */
    private final List<SpecValueRef> pairs;

    /**
     * 构造规格组合（校验：取值对非空、维度名互异）。
     *
     * @param dimensionNames 维度名列表（保持展开序）
     * @param values         对应取值列表（保持展开序）
     */
    public SpecCombination(List<String> dimensionNames, List<String> values) {
        if (dimensionNames == null || values == null
                || dimensionNames.isEmpty() || dimensionNames.size() != values.size()) {
            throw new BusinessException(
                    EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_INVALID.code(), "规格组合非法");
        }
        final List<SpecValueRef> built = new ArrayList<>(dimensionNames.size());
        final Set<String> names = new HashSet<>();
        for (int i = 0; i < dimensionNames.size(); i++) {
            final String name = dimensionNames.get(i);
            final String value = values.get(i);
            if (name == null || name.isBlank()) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_INVALID.code(), "规格组合非法");
            }
            if (value == null || value.isBlank()) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_INVALID.code(), "规格组合非法");
            }
            if (!names.add(name)) {
                throw new BusinessException(
                        EcommerceBusinessCode.CATALOG_PRODUCT_SPEC_INVALID.code(), "规格组合非法");
            }
            built.add(new SpecValueRef(name, value));
        }
        this.pairs = List.copyOf(built);
    }

    /**
     * 取值对快照（维度名 → 取值，不可变副本，保持展开序）。
     *
     * @return 取值对列表
     */
    public List<SpecValueRef> valuesOrdered() {
        return pairs;
    }

    /**
     * 按维度名取值。
     *
     * @param dimensionName 维度名
     * @return 取值；维度不存在返回 null
     */
    public String valueOf(String dimensionName) {
        for (final SpecValueRef pair : pairs) {
            if (pair.getName().equals(dimensionName)) {
                return pair.getValue();
            }
        }
        return null;
    }

    /**
     * 可读摘要（配置序拼接：{@code 维度:值,维度:值}）。
     *
     * @return 摘要文本
     */
    public String summary() {
        final StringBuilder builder = new StringBuilder();
        for (final SpecValueRef pair : pairs) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(pair.getName()).append(':').append(pair.getValue());
        }
        return builder.toString();
    }

    /**
     * 规范化摘要（SHA-256 hex，64 字符，与维度配置顺序无关）。
     *
     * @return 摘要值
     */
    public String hash() {
        final List<SpecValueRef> sorted = new ArrayList<>(pairs);
        sorted.sort(Comparator.comparing(SpecValueRef::getName));
        final StringBuilder canonical = new StringBuilder();
        for (final SpecValueRef pair : sorted) {
            if (!canonical.isEmpty()) {
                canonical.append('\n');
            }
            canonical.append(pair.getName()).append('=').append(pair.getValue());
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }
}