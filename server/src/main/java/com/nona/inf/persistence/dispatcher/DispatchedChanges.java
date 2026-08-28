package com.nona.inf.persistence.dispatcher;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.changeset.ObjectFieldChange;
import com.nona.changeTracking.domain.model.changeset.ValueChange;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 分发后的变更数据结构
 * <p>
 * 将 ChangeSet 分类为：
 * <ul>
 *     <li>主表字段变更</li>
 *     <li>子表/集合变更（按字段名分组）</li>
 * </ul>
 */
@Getter
@ScaffoldGenerated
public class DispatchedChanges {

    /**
     * 主表字段变更列表
     * <p>
     * 元素类型为 {@link ValueChange}（业务值）或 {@link ObjectFieldChange}
     * （对象/集合字段整体替换，ValueNode 承载，无业务值）。
     */
    private final List<Change> mainTableChanges = new ArrayList<>();

    /**
     * 子表/集合变更，按字段名分组
     * <p>
     * key: 字段名（如 "items", "customer"）
     * value: 该字段的所有变更
     */
    private final Map<String, CollectionChanges> childTableChanges = new HashMap<>();

    /**
     * 添加主表字段变更
     *
     * @param change 主表字段变更（ValueChange 或 ObjectFieldChange）
     */
    public void addMainTableChange(Change change) {
        mainTableChanges.add(change);
    }

    /**
     * 获取或创建子表变更。
     *
     * @param fieldName 子表字段名
     * @return 该字段的变更分组
     */
    public CollectionChanges getOrCreateCollectionChanges(String fieldName) {
        return childTableChanges.computeIfAbsent(fieldName, k -> new CollectionChanges());
    }

    /**
     * 子表/集合的变更数据
     */
    @Getter
    public static class CollectionChanges {

        /**
         * 新增项列表
         */
        private final List<ItemAddedChange> additions = new ArrayList<>();

        /**
         * 删除项列表
         */
        private final List<ItemRemovedChange> removals = new ArrayList<>();

        /**
         * 字段变更列表
         * <p>
         * 元素类型为 {@link ValueChange}（业务值）或 {@link ObjectFieldChange}
         * （对象/集合字段整体替换，ValueNode 承载，无业务值）。
         */
        private final List<Change> fieldChanges = new ArrayList<>();

        /**
         * 新增一条新增项变更。
         *
         * @param change 新增项变更
         */
        public void addAddition(ItemAddedChange change) {
            additions.add(change);
        }

        /**
         * 新增一条删除项变更。
         *
         * @param change 删除项变更
         */
        public void addRemoval(ItemRemovedChange change) {
            removals.add(change);
        }

        /**
         * 新增一条字段变更。
         *
         * @param change 字段变更
         */
        public void addFieldChange(Change change) {
            fieldChanges.add(change);
        }
    }

    /**
     * 检查是否为空（没有任何变更）。
     *
     * @return 没有任何变更时返回 true
     */
    public boolean isEmpty() {
        return mainTableChanges.isEmpty() && childTableChanges.isEmpty();
    }
}
