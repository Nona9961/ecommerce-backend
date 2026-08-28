package com.nona.inf.persistence.reconstructor;

import com.nona.changeTracking.domain.model.changeset.Change;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ItemAddedChange;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedChange;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.inf.persistence.converters.CompositePoConverter;
import com.nona.inf.persistence.converters.ConverterRegistry;
import com.nona.inf.persistence.converters.PoConverter;
import com.nona.inf.persistence.dispatcher.ChangeDispatcher;
import com.nona.inf.persistence.dispatcher.DispatchedChanges;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.nona.annotation.ScaffoldGenerated;

/**
 * PO 重建器
 * <p>
 * 从聚合根 (Root) 和变更集 (ChangeSet) 重建需要持久化的 PO 对象。
 * </p>
 * <p>
 * 职责：
 * <ul>
 *     <li>根据 ChangeSet 识别哪些 PO 需要保存（新增/更新）</li>
 *     <li>根据 ChangeSet 识别哪些 PO 需要删除</li>
 *     <li>通过 ConverterRegistry 获取对应的转换器，将领域对象转换为 PO</li>
 * </ul>
 * </p>
 * <p>
 * 使用方式：
 * <pre>{@code
 * ReconstructedPos pos = poReconstructor.reconstruct(order, changeSet);
 * orderPORepo.saveAll(pos.getToSave(OrderPO.class));
 * orderItemPORepo.saveAll(pos.getToSave(OrderItemPO.class));
 * orderItemPORepo.deleteAllById(pos.getToDeleteIds(OrderItemPO.class));
 * }</pre>
 * </p>
 *
 * @author nona
 */
@RequiredArgsConstructor
@ScaffoldGenerated
public class PoReconstructor {

    /**
     * 匹配路径中的集合项标识符（如 {@code items[123]} → {@code 123}）
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\w+\\[(.+?)]");

    /**
     * 转换器注册中心
     */
    private final ConverterRegistry converterRegistry;

    /**
     * 变更分发器
     */
    private final ChangeDispatcher changeDispatcher;

    /**
     * 从 Root 和 ChangeSet 重建 PO 对象
     *
     * @param root      聚合根
     * @param changeSet 变更集
     * @return 重建后的 PO 对象
     */
    public ReconstructedPos reconstruct(Object root, ChangeSet changeSet) {
        final List<Object> toSave = new ArrayList<>();
        final List<DeletionInfo> toDelete = new ArrayList<>();
        final Class<?> rootClass = root.getClass();

        final DispatchedChanges dispatched = changeDispatcher.dispatch(changeSet, rootClass);

        if (!dispatched.getMainTableChanges().isEmpty()) {
            converterRegistry.getCompositeConverter(rootClass)
                    .ifPresent(converter -> toSave.add(convertToMainPO(converter, root)));
        }

        final Map<String, PoConverter<?, ?>> allConverters = converterRegistry.getAllConverters();
        for (final Map.Entry<String, DispatchedChanges.CollectionChanges> entry : dispatched.getChildTableChanges().entrySet()) {
            final String fieldName = entry.getKey();
            final DispatchedChanges.CollectionChanges changes = entry.getValue();
            final PoConverter<?, ?> converter = allConverters.get(fieldName);

            if (converter != null) {
                processChildChanges(root, fieldName, changes, converter, toSave, toDelete);
            }
        }

        return new ReconstructedPos(toSave, toDelete);
    }

    /**
     * 处理子表变更
     * <p>
     * 根据 CollectionChanges 中的新增、删除、更新信息，
     * 将对应的领域对象转换为 PO 并分类到 toSave 或 toDelete 列表中。
     * </p>
     *
     * @param root       聚合根对象
     * @param fieldName  子表对应的字段名
     * @param changes    该字段的变更信息
     * @param converter  领域对象到 PO 的转换器
     * @param toSave     输出：需要保存的 PO 列表
     * @param toDelete   输出：需要删除的 PO 信息列表
     */
    private void processChildChanges(
            Object root,
            String fieldName,
            DispatchedChanges.CollectionChanges changes,
            PoConverter<?, ?> converter,
            List<Object> toSave,
            List<DeletionInfo> toDelete) {

        for (final ItemAddedChange iac : changes.getAdditions()) {
            final Object identifier = ((ObjectNode) iac.addedItem()).identifier();
            final Object childDO = findChildFromRoot(root, fieldName, identifier);
            if (childDO != null) {
                toSave.add(convertToPO(converter, childDO));
            }
        }

        for (final ItemRemovedChange irc : changes.getRemovals()) {
            final Object identifier = ((ObjectNode) irc.removedItem()).identifier();
            toDelete.add(new DeletionInfo(converter.poClass(), (Long) identifier));
        }

        final Set<Object> updatedIdentifiers = extractIdentifiersFromFieldChanges(changes.getFieldChanges());
        for (final Object identifier : updatedIdentifiers) {
            final Object childDO = findChildFromRoot(root, fieldName, identifier);
            if (childDO != null) {
                toSave.add(convertToPO(converter, childDO));
            }
        }
    }

    /**
     * 将聚合根转换为主表 PO。
     *
     * @param converter 组合转换器
     * @param root      聚合根
     * @return 主表 PO
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertToMainPO(CompositePoConverter converter, Object root) {
        return converter.toMainPO(root);
    }

    /**
     * 将子实体转换为其 PO。
     *
     * @param converter    转换器
     * @param domainObject 领域对象
     * @return PO 对象
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertToPO(PoConverter converter, Object domainObject) {
        return converter.toPO(domainObject);
    }

    /**
     * 从字段变更的完整路径中提取集合项标识符集合。
     *
     * @param fieldChanges 字段变更列表
     * @return 标识符集合（Long 优先，解析失败时保留字符串）
     */
    private Set<Object> extractIdentifiersFromFieldChanges(List<Change> fieldChanges) {
        final Set<Object> identifiers = new HashSet<>();
        for (final Change change : fieldChanges) {
            final Matcher matcher = IDENTIFIER_PATTERN.matcher(change.fullPath());
            if (matcher.find()) {
                final String idStr = matcher.group(1);
                try {
                    identifiers.add(Long.parseLong(idStr));
                } catch (NumberFormatException e) {
                    identifiers.add(idStr);
                }
            }
        }
        return identifiers;
    }

    /**
     * 从聚合根对象图中查找指定字段名与标识符匹配的子对象。
     *
     * @param root       聚合根
     * @param fieldName  子表字段名
     * @param identifier 业务标识符
     * @return 匹配的子对象；未找到时返回 null
     */
    private Object findChildFromRoot(Object root, String fieldName, Object identifier) {
        return findChildRecursively(root, fieldName, identifier, new HashSet<>());
    }

    /**
     * 递归查找子对象
     * <p>
     * 在对象图中递归查找指定字段名和标识符的子对象。
     * </p>
     */
    private Object findChildRecursively(Object current, String fieldName, Object identifier, Set<Object> visited) {
        if (current == null || visited.contains(current)) {
            return null;
        }
        visited.add(current);

        try {
            final Field field = findField(current.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                final Object fieldValue = field.get(current);

                if (fieldValue instanceof Collection<?> collection) {
                    for (final Object item : collection) {
                        final Object itemId = extractIdentifier(item);
                        if (Objects.equals(itemId, identifier)) {
                            return item;
                        }
                    }
                }
            }
        } catch (IllegalAccessException ignored) {
        }

        for (final Field field : getAllFields(current.getClass())) {
            try {
                field.setAccessible(true);
                final Object fieldValue = field.get(current);

                if (fieldValue instanceof Collection<?> collection) {
                    for (final Object item : collection) {
                        final Object found = findChildRecursively(item, fieldName, identifier, visited);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return null;
    }

    /**
     * 获取类的所有字段（包括父类）。
     *
     * @param clazz 目标类
     * @return 字段列表
     */
    private List<Field> getAllFields(Class<?> clazz) {
        final List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 在类继承链中查找字段。
     *
     * @param clazz     目标类
     * @param fieldName 字段名
     * @return 字段；不存在时返回 null
     */
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 提取对象的 id 字段值。
     *
     * @param item 对象
     * @return id 值；无法访问时返回 null
     */
    private Object extractIdentifier(Object item) {
        try {
            final Field idField = findField(item.getClass(), "id");
            if (idField != null) {
                idField.setAccessible(true);
                return idField.get(item);
            }
        } catch (IllegalAccessException ignored) {
        }
        return null;
    }
}
