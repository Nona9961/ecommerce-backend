package com.nona.inf.persistence.tracking;

import com.nona.annotation.ScaffoldGenerated;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;

/**
 * ChangeTracker 提供者
 * <p>
 * 根据 {@link ChangeTrackingProperties} 配置创建 {@link ChangeTracker} 实例。
 * 每次调用 {@link #create()} 返回一个新的 ChangeTracker 实例。
 * <p>
 * 该类封装了 change-tracking 库的配置细节，对外提供简单的工厂方法。
 */
@Slf4j
@ScaffoldGenerated
public class ChangeTrackerProvider {

    /**
     * 默认追踪能力名称（change-tracking 的反射默认实现）
     */
    private static final String DEFAULT_CAPABILITY_NAME = "default-reflection";

    /**
     * 标识符提取器映射（类 → 提取函数）
     */
    private final Map<Class<?>, Function<Object, Object>> extractors;

    /**
     * 值类型集合
     */
    private final Set<Class<?>> valueTypes;

    /**
     * 值类型包集合
     */
    private final Set<String> valuePackages;

    /**
     * 指定的追踪能力名称（可为 null，表示默认选择策略）
     */
    private final String capabilityName;

    /**
     * 缓存的 TrackingCapabilityProvider 类，延迟初始化
     */
    private volatile Class<? extends TrackingCapabilityProvider> providerClass;

    /**
     * 从配置属性创建提供者
     *
     * @param properties 配置属性
     */
    public ChangeTrackerProvider(ChangeTrackingProperties properties) {
        Objects.requireNonNull(properties, "properties cannot be null");

        IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(properties);
        this.extractors = builder.build();
        this.valueTypes = resolveValueTypes(properties);
        this.valuePackages = new HashSet<>(properties.getValueTypePackages());
        this.capabilityName = normalizeCapabilityName(properties.getCapability());
    }

    /**
     * 使用预构建的配置创建提供者
     *
     * @param extractors    标识符提取器映射
     * @param valueTypes    值类型集合
     * @param valuePackages 值类型包集合
     */
    public ChangeTrackerProvider(
            Map<Class<?>, Function<Object, Object>> extractors,
            Set<Class<?>> valueTypes,
            Set<String> valuePackages) {
        this(null, extractors, valueTypes, valuePackages);
    }

    /**
     * 完整配置构造器（Builder 内部使用）：复制输入集合以保持内部状态隔离。
     *
     * @param capabilityName 指定的能力名称；可为 null
     * @param extractors     标识符提取器映射
     * @param valueTypes     值类型集合
     * @param valuePackages  值类型包集合
     */
    private ChangeTrackerProvider(
            String capabilityName,
            Map<Class<?>, Function<Object, Object>> extractors,
            Set<Class<?>> valueTypes,
            Set<String> valuePackages) {
        this.extractors = new HashMap<>(Objects.requireNonNull(extractors));
        this.valueTypes = new HashSet<>(Objects.requireNonNull(valueTypes));
        this.valuePackages = new HashSet<>(Objects.requireNonNull(valuePackages));
        this.capabilityName = normalizeCapabilityName(capabilityName);
    }

    /**
     * 创建配置好的追踪能力单元（{@link TrackingCapability}）。
     * <p>
     * 内部配置装配路径（SPI 能力发现 / 名称选择、标识符提取器、值类型、值类型包）的
     * 统一出口——{@link #create()} 与异步基线重建
     * （{@code ChangeTracker.fromBaseline(createCapability(), baseline)}——tracker
     * 首次创建钩子）复用本路径，装配行为一致。
     * <p>
     * 每次调用返回一个新的能力实例（与 {@link #create()} 的每次新实例语义一致）。
     *
     * @return 配置好的 TrackingCapability 实例
     */
    public TrackingCapability<?> createCapability() {
        if (providerClass == null) {
            synchronized (this) {
                if (providerClass == null) {
                    providerClass = discoverProviderClass();
                }
            }
        }

        final TrackingCapabilityProvider provider = createProviderInstance();

        for (final Map.Entry<Class<?>, Function<Object, Object>> entry : extractors.entrySet()) {
            registerIdentifier(provider, entry.getKey(), entry.getValue());
        }

        for (final Class<?> valueType : valueTypes) {
            provider.withValueType(valueType);
        }

        for (final String packageName : valuePackages) {
            provider.withValuePackage(packageName);
        }

        return provider.create();
    }

    /**
     * 创建新的 ChangeTracker 实例
     * <p>
     * 每次调用返回一个独立的实例，适用于请求级别的生命周期管理。
     *
     * @return 配置好的 ChangeTracker 实例
     */
    public ChangeTracker create() {
        return new ChangeTracker(createCapability());
    }

    /**
     * 通过 SPI 发现 TrackingCapabilityProvider 实现类
     *
     * @return Provider 实现类
     * @throws IllegalStateException 如果没有找到实现
     */
    private Class<? extends TrackingCapabilityProvider> discoverProviderClass() {
        final ServiceLoader<TrackingCapabilityProvider> loader = ServiceLoader.load(TrackingCapabilityProvider.class);
        final Map<String, Class<? extends TrackingCapabilityProvider>> discovered = new HashMap<>();

        for (final TrackingCapabilityProvider provider : loader) {
            final String name = normalizeCapabilityName(provider.getName());
            if (name == null) {
                throw new IllegalStateException("TrackingCapabilityProvider name is blank: " + provider.getClass().getName());
            }
            final Class<? extends TrackingCapabilityProvider> existing = discovered.putIfAbsent(name, provider.getClass());
            if (existing != null && !existing.equals(provider.getClass())) {
                throw new IllegalStateException(String.format(
                        "Multiple TrackingCapabilityProviders found with the same name '%s': %s, %s",
                        name, existing.getName(), provider.getClass().getName()));
            }
        }

        if (discovered.isEmpty()) {
            throw new IllegalStateException("No TrackingCapabilityProvider found. Ensure change-tracking-core is on the classpath.");
        }

        final String selectedName = selectCapabilityName(this.capabilityName, discovered.keySet());
        final Class<? extends TrackingCapabilityProvider> providerClass = discovered.get(selectedName);
        if (providerClass == null) {
            throw new IllegalStateException("Selected tracking capability not found: " + selectedName);
        }
        return providerClass;
    }

    /**
     * 按配置与可用能力选择最终使用的能力名称。
     *
     * @param configuredCapabilityName 配置的能力名称；可为 null
     * @param availableNames           可用能力名称集合
     * @return 选中的能力名称
     * @throws IllegalArgumentException 配置的能力不存在时抛出
     * @throws IllegalStateException    无可用能力时抛出
     */
    private static String selectCapabilityName(final String configuredCapabilityName, final Set<String> availableNames) {
        final String configured = normalizeCapabilityName(configuredCapabilityName);
        if (configured != null) {
            if (!availableNames.contains(configured)) {
                throw new IllegalArgumentException(String.format(
                        "Tracking capability with name '%s' not found. Available capabilities: %s",
                        configured, availableNames.stream().sorted().toList()));
            }
            return configured;
        }

        if (availableNames.contains(DEFAULT_CAPABILITY_NAME)) {
            return DEFAULT_CAPABILITY_NAME;
        }

        return availableNames.stream()
                .sorted()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No tracking capabilities available."));
    }

    /**
     * 规范化能力名称（去空白；空串视为未配置）。
     *
     * @param name 原始名称
     * @return 规范化名称；未配置时返回 null
     */
    private static String normalizeCapabilityName(final String name) {
        if (name == null) {
            return null;
        }
        final String trimmed = name.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed;
    }

    /**
     * 创建新的 Provider 实例
     *
     * @return 新的 Provider 实例
     */
    private TrackingCapabilityProvider createProviderInstance() {
        try {
            return providerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create TrackingCapabilityProvider instance", e);
        }
    }

    /**
     * 注册标识符提取器到 Provider
     * <p>
     * 使用 unchecked cast 是因为 TrackingCapabilityProvider 的 API 设计需要泛型类型参数
     */
    @SuppressWarnings("unchecked")
    private <T> void registerIdentifier(
            TrackingCapabilityProvider provider,
            Class<?> clazz,
            Function<Object, Object> extractor) {
        provider.withIdentifier((Class<T>) clazz, extractor::apply);
    }

    /**
     * 从配置解析值类型。
     *
     * @param properties 配置属性
     * @return 值类型类集合
     * @throws IllegalStateException 如果配置的类不存在
     */
    private Set<Class<?>> resolveValueTypes(ChangeTrackingProperties properties) {
        Set<Class<?>> types = new HashSet<>();
        for (String className : properties.getValueTypes()) {
            types.add(resolveValueType(className));
        }
        return types;
    }

    /**
     * 按类名解析值类型；类不存在时抛出清晰的配置错误。
     *
     * @param className 值类型全限定类名
     * @return 解析出的值类型类
     * @throws IllegalStateException 配置的类不存在时抛出
     */
    private static Class<?> resolveValueType(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    String.format("Value type class '%s' not found. " +
                            "Please check your change-tracking configuration.", className), e);
        }
    }

    /**
     * 获取当前配置的提取器映射（只读视图）
     *
     * @return 提取器映射的不可变视图
     */
    public Map<Class<?>, Function<Object, Object>> getExtractors() {
        return Collections.unmodifiableMap(extractors);
    }

    /**
     * 获取当前配置的值类型（只读视图）
     *
     * @return 值类型集合的不可变视图
     */
    public Set<Class<?>> getValueTypes() {
        return Collections.unmodifiableSet(valueTypes);
    }

    /**
     * 获取当前配置的值类型包（只读视图）
     *
     * @return 值类型包集合的不可变视图
     */
    public Set<String> getValuePackages() {
        return Collections.unmodifiableSet(valuePackages);
    }

    /**
     * 使用 Builder 模式创建提供者。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 提供者的流式构建器。
     */
    public static class Builder {

        /**
         * 标识符提取器映射
         */
        private final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();

        /**
         * 值类型集合
         */
        private final Set<Class<?>> valueTypes = new HashSet<>();

        /**
         * 值类型包集合
         */
        private final Set<String> valuePackages = new HashSet<>();

        /**
         * 指定的能力名称（可为 null）
         */
        private String capabilityName;

        /**
         * 私有构造器，仅通过 {@link ChangeTrackerProvider#builder()} 创建。
         */
        private Builder() {}

        /**
         * 从配置属性加载提取器、值类型与能力名称。
         *
         * @param properties 配置属性
         * @return 当前构建器，支持链式调用
         * @throws IllegalStateException 配置的类不存在时抛出
         */
        public Builder fromProperties(ChangeTrackingProperties properties) {
            IdentifierExtractorBuilder builder = new IdentifierExtractorBuilder(properties);
            this.extractors.putAll(builder.build());
            this.capabilityName = normalizeCapabilityName(properties.getCapability());
            properties.getValueTypes().forEach(className ->
                    this.valueTypes.add(resolveValueType(className)));
            this.valuePackages.addAll(properties.getValueTypePackages());
            return this;
        }

        /**
         * 指定使用的追踪能力名称（SPI Provider 名称）。
         *
         * @param capabilityName 能力名称；可为 null（使用默认选择策略）
         * @return 当前构建器，支持链式调用
         */
        public Builder capability(final String capabilityName) {
            this.capabilityName = normalizeCapabilityName(capabilityName);
            return this;
        }

        /**
         * 添加标识符提取器。
         *
         * @param clazz     目标类
         * @param extractor 提取函数
         * @return 当前构建器，支持链式调用
         * @param <T> 目标类类型
         */
        public <T> Builder withIdentifier(Class<T> clazz, Function<T, Object> extractor) {
            this.extractors.put(clazz, obj -> extractor.apply((T) obj));
            return this;
        }

        /**
         * 添加值类型。
         *
         * @param clazz 值类型类（必须不可变）
         * @return 当前构建器，支持链式调用
         */
        public Builder withValueType(Class<?> clazz) {
            this.valueTypes.add(clazz);
            return this;
        }

        /**
         * 添加值类型包。
         *
         * @param packageName 值类型包名
         * @return 当前构建器，支持链式调用
         */
        public Builder withValuePackage(String packageName) {
            this.valuePackages.add(packageName);
            return this;
        }

        /**
         * 构建提供者。
         *
         * @return 配置好的 ChangeTrackerProvider
         */
        public ChangeTrackerProvider build() {
            return new ChangeTrackerProvider(capabilityName, extractors, valueTypes, valuePackages);
        }
    }
}
