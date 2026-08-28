package com.nona.inf.persistence.tracking;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.nona.annotation.ScaffoldGenerated;

/**
 * 标识符提取器构建器
 * <p>
 * 根据 {@link ChangeTrackingProperties} 配置构建标识符提取器映射。
 * 支持：
 * <ul>
 *     <li>方法提取器（优先级最高）</li>
 *     <li>字段提取器（覆盖配置）</li>
 *     <li>默认字段提取器</li>
 * </ul>
 */
@Slf4j
@ScaffoldGenerated
public class IdentifierExtractorBuilder {

    private final ChangeTrackingProperties properties;

    /**
     * 创建构建器实例
     *
     * @param properties 配置属性
     */
    public IdentifierExtractorBuilder(ChangeTrackingProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    /**
     * 构建标识符提取器映射
     * <p>
     * 处理顺序：
     * 1. 方法配置（identifierMethods）- 优先级最高
     * 2. 字段覆盖配置（identifierOverrides）
     *
     * @return 类型到提取器的映射
     */
    public Map<Class<?>, Function<Object, Object>> build() {
        final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();

        for (final Map.Entry<String, String> entry : properties.getIdentifierMethods().entrySet()) {
            final Class<?> clazz = loadClassOrThrow(entry.getKey(), "identifier method");
            final Function<Object, Object> extractor = createMethodExtractor(clazz, entry.getValue());
            extractors.put(clazz, extractor);
        }

        for (final Map.Entry<String, String> entry : properties.getIdentifierOverrides().entrySet()) {
            final Class<?> clazz = loadClassOrThrow(entry.getKey(), "identifier override");
            if (!extractors.containsKey(clazz)) {
                final Function<Object, Object> extractor = createFieldExtractor(clazz, entry.getValue());
                extractors.put(clazz, extractor);
            }
        }

        return extractors;
    }

    /**
     * 获取默认提取器。
     * <p>
     * 使用全局默认标识符字段名尝试提取，如果失败则回退到 identityHashCode。
     *
     * @return 默认提取器函数
     */
    public Function<Object, Object> getDefaultExtractor() {
        final String defaultFieldName = properties.getDefaultIdentifier();
        return obj -> {
            if (obj == null) {
                return null;
            }
            try {
                final Field field = findField(obj.getClass(), defaultFieldName);
                if (field != null) {
                    field.setAccessible(true);
                    return field.get(obj);
                }
            } catch (Exception e) {
            }
            return System.identityHashCode(obj);
        };
    }

    /**
     * 为特定类获取提取器。
     * <p>
     * 优先级：已注册的提取器 > 默认字段 > identityHashCode。
     *
     * @param extractors 已构建的提取器映射
     * @param clazz      目标类
     * @return 提取器函数
     */
    public Function<Object, Object> getExtractorFor(
            Map<Class<?>, Function<Object, Object>> extractors,
            Class<?> clazz) {
        if (extractors.containsKey(clazz)) {
            return extractors.get(clazz);
        }

        for (final Map.Entry<Class<?>, Function<Object, Object>> entry : extractors.entrySet()) {
            if (entry.getKey().isAssignableFrom(clazz)) {
                return entry.getValue();
            }
        }

        final String defaultFieldName = properties.getDefaultIdentifier();
        final Field field = findField(clazz, defaultFieldName);
        if (field != null) {
            return createFieldExtractor(clazz, defaultFieldName);
        }

        return System::identityHashCode;
    }

    /**
     * 创建字段提取器（通过反射读取字段值）。
     *
     * @param clazz     目标类
     * @param fieldName 字段名
     * @return 字段提取器函数
     */
    private Function<Object, Object> createFieldExtractor(Class<?> clazz, String fieldName) {
        return obj -> {
            if (obj == null) {
                return null;
            }
            try {
                final Field field = findField(obj.getClass(), fieldName);
                if (field == null) {
                    throw new IllegalStateException(
                            "Field '" + fieldName + "' not found in " + obj.getClass().getName());
                }
                field.setAccessible(true);
                return field.get(obj);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to access field: " + fieldName, e);
            }
        };
    }

    /**
     * 创建方法提取器（通过反射调用无参方法）。
     *
     * @param clazz      目标类
     * @param methodName 方法名
     * @return 方法提取器函数
     */
    private Function<Object, Object> createMethodExtractor(Class<?> clazz, String methodName) {
        return obj -> {
            if (obj == null) {
                return null;
            }
            try {
                final Method method = findMethod(obj.getClass(), methodName);
                if (method == null) {
                    throw new IllegalStateException(
                            "Method '" + methodName + "' not found in " + obj.getClass().getName());
                }
                method.setAccessible(true);
                return method.invoke(obj);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke method: " + methodName, e);
            }
        };
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
     * 在类继承链中查找无参方法。
     *
     * @param clazz      目标类
     * @param methodName 方法名
     * @return 方法；不存在时返回 null
     */
    private Method findMethod(Class<?> clazz, String methodName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 加载类，如果不存在则抛出异常
     *
     * @param className  类全限定名
     * @param configType 配置类型描述（用于错误信息）
     * @return 加载的类
     * @throws IllegalStateException 如果类不存在
     */
    private Class<?> loadClassOrThrow(String className, String configType) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    String.format("Class '%s' configured for %s not found. " +
                            "Please check your change-tracking configuration.", className, configType), e);
        }
    }
}
