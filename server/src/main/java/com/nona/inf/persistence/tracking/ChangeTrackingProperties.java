package com.nona.inf.persistence.tracking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;
import com.nona.annotation.ScaffoldGenerated;

/**
 * change-tracking 配置属性（前缀 {@code change-tracking}）。
 * <p>
 * 用于配置默认标识符、值类型、标识符提取器与追踪能力。
 */
@Data
@ConfigurationProperties(prefix = "change-tracking")
@ScaffoldGenerated
public class ChangeTrackingProperties {

    /**
     * 指定使用的追踪能力名称（SPI Provider 名称）。
     * <p>
     * 对应 {@code com.nona.changeTracking.spi.TrackingCapabilityProvider#getName()}。
     * <p>
     * 不配置时默认选择策略与 change-tracking 保持一致：
     * <ul>
     *     <li>优先选择 {@code default-reflection}</li>
     *     <li>否则按名称排序选择第一个</li>
     * </ul>
     */
    private String capability;

    /**
     * 默认标识符字段名（提取器回退路径）
     */
    private String defaultIdentifier = "id";

    /**
     * 值类型包列表（包下所有类视为值类型，不展开追踪）
     */
    private List<String> valueTypePackages = new ArrayList<>();

    /**
     * 值类型类列表（必须不可变）
     */
    private List<String> valueTypes = new ArrayList<>();

    /**
     * 字段覆盖配置（类全限定名 → 字段名）
     */
    private Map<String, String> identifierOverrides = new HashMap<>();

    /**
     * 方法提取器配置（类全限定名 → 方法名，优先级最高）
     */
    private Map<String, String> identifierMethods = new HashMap<>();
}
