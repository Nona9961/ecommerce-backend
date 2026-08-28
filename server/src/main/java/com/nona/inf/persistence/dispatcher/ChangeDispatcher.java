package com.nona.inf.persistence.dispatcher;

import com.nona.changeTracking.domain.model.changeset.*;
import com.nona.inf.persistence.converters.ConverterRegistry;
import com.nona.inf.persistence.converters.PoConverter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 变更分发器
 * <p>
 * 职责：将 ChangeSet 分类为主表变更和子表变更，返回结构化的数据。
 * <p>
 * 利用 {@link ChangeSet#getLeafChanges()} 和 {@link Change#collectionFieldName()} 进行分类。
 */
@RequiredArgsConstructor
@ScaffoldGenerated
public class ChangeDispatcher {

    /**
     * 转换器注册中心（用于判断子表字段）
     */
    private final ConverterRegistry converterRegistry;

    /**
     * 将变更集分发为主表变更与子表变更。
     *
     * @param changeSet 变更集
     * @param rootClass 聚合根类型
     * @return 分类后的变更数据
     */
    public DispatchedChanges dispatch(ChangeSet changeSet, Class<?> rootClass) {
        final DispatchedChanges result = new DispatchedChanges();
        final Map<String, PoConverter<?, ?>> converters = converterRegistry.getAllConverters();

        for (final Change change : changeSet.getLeafChanges()) {
            final String collectionField = change.collectionFieldName();

            if (collectionField != null && converters.containsKey(collectionField)) {
                categorizeChildChange(change, collectionField, result);
            } else if (change instanceof ValueChange vc) {
                result.addMainTableChange(vc);
            } else if (change instanceof ObjectFieldChange ofc) {
                // 对象/集合字段整体替换（跨类型变化）：ValueNode 承载，无业务值
                result.addMainTableChange(ofc);
            }
        }

        return result;
    }

    /**
     * 将子表字段的变更归类到对应集合变更分组中。
     *
     * @param change    变更项
     * @param fieldName 子表字段名
     * @param result    分类结果容器
     */
    private void categorizeChildChange(Change change, String fieldName, DispatchedChanges result) {
        final DispatchedChanges.CollectionChanges collectionChanges = result.getOrCreateCollectionChanges(fieldName);

        switch (change) {
            case ItemAddedChange iac -> collectionChanges.addAddition(iac);
            case ItemRemovedChange irc -> collectionChanges.addRemoval(irc);
            case ValueChange vc -> collectionChanges.addFieldChange(vc);
            case ObjectFieldChange ofc -> collectionChanges.addFieldChange(ofc);
            default -> {}
        }
    }
}
