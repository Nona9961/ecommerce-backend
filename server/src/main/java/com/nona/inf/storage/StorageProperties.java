package com.nona.inf.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

/**
 * 文件存储配置（{@code nona.storage.*}）：根目录、大小上限、contentType 白名单。
 * <p>
 * 根目录可配置（application.yml / 环境变量 FILES_ROOT_DIR；容器挂 volume，
 * 重建不丢）；大小上限与白名单按电商图片场景取值（主图/详情图），均可调。
 *
 * @param rootDir            存储根目录（相对/绝对路径，启动时自动创建）
 * @param maxSizeBytes       单文件大小上限（字节），默认 10MB
 * @param allowedContentTypes 允许的 MIME 类型白名单（大小写不敏感）
 * @author nona9961
 */
@ConfigurationProperties(prefix = "nona.storage")
public record StorageProperties(
        String rootDir,
        long maxSizeBytes,
        List<String> allowedContentTypes
) {

    /**
     * 紧凑构造：配置非法直接启动失败（fail-fast）。
     */
    public StorageProperties {
        Objects.requireNonNull(rootDir, "rootDir 不能为空");
        if (maxSizeBytes <= 0 || maxSizeBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxSizeBytes 必须在 (0, 2^31-1] 区间");
        }
        Objects.requireNonNull(allowedContentTypes, "allowedContentTypes 不能为空");
        allowedContentTypes = allowedContentTypes.stream().map(String::toLowerCase).toList();
    }
}