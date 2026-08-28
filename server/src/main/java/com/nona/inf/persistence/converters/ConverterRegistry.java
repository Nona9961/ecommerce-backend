package com.nona.inf.persistence.converters;

import com.nona.inf.persistence.po.BasePO;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 转换器注册中心
 * <p>
 * 支持：
 * <ul>
 *     <li>简单转换器注册与查询</li>
 *     <li>组合转换器注册与查询</li>
 *     <li>子表自动扫描（基于已注册的 PoConverter）</li>
 *     <li>手动声明覆盖自动扫描</li>
 * </ul>
 */
@ScaffoldGenerated
public class ConverterRegistry {

    /**
     * 简单转换器注册表（key: 领域类）
     */
    private final Map<Class<?>, PoConverter<?, ?>> simpleConverters = new ConcurrentHashMap<>();

    /**
     * 组合转换器注册表（key: 聚合根类）
     */
    private final Map<Class<?>, CompositePoConverter<?, ?>> compositeConverters = new ConcurrentHashMap<>();

    /**
     * 注册简单转换器。
     *
     * @param converter 简单转换器
     * @param <DO>      领域对象类型
     * @param <PO>      持久化对象类型
     */
    public <DO, PO> void register(PoConverter<DO, PO> converter) {
        simpleConverters.put(converter.domainClass(), converter);
    }

    /**
     * 注册组合转换器。
     *
     * @param converter 组合转换器
     * @param <Root>    聚合根类型
     * @param <MainPO>  主表 PO 类型
     */
    public <Root, MainPO extends BasePO> void register(CompositePoConverter<Root, MainPO> converter) {
        compositeConverters.put(converter.rootClass(), converter);
    }

    /**
     * 获取简单转换器。
     *
     * @param domainClass 领域类
     * @return 匹配的转换器；未注册时为空
     * @param <DO> 领域对象类型
     * @param <PO> 持久化对象类型
     */
    @SuppressWarnings("unchecked")
    public <DO, PO> Optional<PoConverter<DO, PO>> getConverter(Class<DO> domainClass) {
        return Optional.ofNullable((PoConverter<DO, PO>) simpleConverters.get(domainClass));
    }

    /**
     * 获取组合转换器。
     *
     * @param rootClass 聚合根类
     * @return 匹配的组合转换器；未注册时为空
     * @param <Root>   聚合根类型
     * @param <MainPO> 主表 PO 类型
     */
    @SuppressWarnings("unchecked")
    public <Root, MainPO extends BasePO> Optional<CompositePoConverter<Root, MainPO>>
            getCompositeConverter(Class<Root> rootClass) {
        return Optional.ofNullable((CompositePoConverter<Root, MainPO>) compositeConverters.get(rootClass));
    }

    /**
     * 获取聚合根的子实体映射（自动扫描 + 手动声明合并）
     *
     * @param rootClass 聚合根类型
     * @return 属性路径 -> 转换器 的映射
     */
    public Map<String, PoConverter<?, ?>> getChildConverters(Class<?> rootClass) {
        final Map<String, PoConverter<?, ?>> result = scanChildConverters(rootClass);

        final CompositePoConverter<?, ?> converter = compositeConverters.get(rootClass);
        if (converter != null) {
            result.putAll(converter.declareChildConverters());
        }

        return result;
    }

    /**
     * 获取所有字段名到转换器的映射（递归扫描所有已注册的领域类）
     *
     * @return 字段名 -> 转换器 的映射
     */
    public Map<String, PoConverter<?, ?>> getAllConverters() {
        final Map<String, PoConverter<?, ?>> result = new HashMap<>();

        for (final CompositePoConverter<?, ?> converter : compositeConverters.values()) {
            scanFieldsRecursively(converter.rootClass(), result);
        }

        for (final PoConverter<?, ?> converter : simpleConverters.values()) {
            scanFieldsRecursively(converter.domainClass(), result);
        }

        return result;
    }

    /**
     * 递归扫描类的字段，建立字段名到转换器的映射。
     *
     * @param clazz  目标类
     * @param result 输出映射（字段名 → 转换器）
     */
    private void scanFieldsRecursively(Class<?> clazz, Map<String, PoConverter<?, ?>> result) {
        for (final Field field : getAllFields(clazz)) {
            final Class<?> elementType = extractElementType(field);
            if (elementType != null) {
                final PoConverter<?, ?> converter = simpleConverters.get(elementType);
                if (converter != null) {
                    result.put(field.getName(), converter);
                    scanFieldsRecursively(elementType, result);
                }
            }
        }
    }

    /**
     * 自动扫描聚合根类的字段，识别子表
     */
    private Map<String, PoConverter<?, ?>> scanChildConverters(Class<?> rootClass) {
        final Map<String, PoConverter<?, ?>> result = new HashMap<>();
        for (final Field field : getAllFields(rootClass)) {
            final Class<?> elementType = extractElementType(field);
            if (elementType != null) {
                final PoConverter<?, ?> converter = simpleConverters.get(elementType);
                if (converter != null) {
                    result.put(field.getName(), converter);
                }
            }
        }
        return result;
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
     * 提取字段的元素类型。
     *
     * @param field 字段
     * @return 元素类型；无法识别时返回 null
     */
    private Class<?> extractElementType(Field field) {
        Class<?> fieldType = field.getType();

        if (Collection.class.isAssignableFrom(fieldType)) {
            final Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> clazz) {
                    return clazz;
                }
            }
            return null;
        }

        return fieldType;
    }
}
