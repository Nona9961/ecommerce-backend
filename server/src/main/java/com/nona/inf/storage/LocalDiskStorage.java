package com.nona.inf.storage;

import com.nona.exceptions.BusinessException;
import com.nona.exceptions.EcommerceBusinessCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 本地磁盘文件存储：根目录可配置，容器挂 volume 重建不丢。
 * <p>
 * objectKey 形态 {@code yyyy/MM/dd/{snowflake}{ext}}（日期分目录 + 扩展名由
 * contentType 映射）。路径穿越防御（HARD）：所有入参 objectKey 经白名单字符集
 * 校验 + 分段拒绝 {@code ..} / 绝对路径 + normalize 后必须留在根目录内，
 * 上传文件名不参与路径拼接（key 完全由系统生成）。
 * 实例由 {@link StorageConfig} 注册为 {@link FileStorage} Bean（本类不标注 @Component，
 * 避免与配置类 @Bean 双注册）。
 *
 * @author nona9961
 */
@Slf4j
public class LocalDiskStorage implements FileStorage {

    /**
     * key 单段合法字符：字母数字开头，可含 . _ -（不包含路径分隔符语义字符）。
     */
    private static final Pattern KEY_SEGMENT = Pattern.compile("^[0-9a-zA-Z][0-9a-zA-Z._-]*$");

    /**
     * objectKey 总长度上限（日期目录 + snowflake + 扩展名 ≈ 40 字符；
     * 256 为宽松上限，防御超长 key 构造无意义深层路径的 DoS 面）。
     */
    private static final int MAX_KEY_LENGTH = 256;

    /**
     * 已知图片 MIME → 扩展名映射（覆盖默认白名单与常见图片类型）。
     */
    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif",
            "image/bmp", ".bmp",
            "image/x-icon", ".ico",
            "image/tiff", ".tiff",
            "image/avif", ".avif"
    );

    /**
     * 兜底扩展名清洗：仅保留 [0-9a-z]（MIME subtype 派生，保证落入 key 字符集）。
     */
    private static final Pattern EXT_SANITIZE = Pattern.compile("[^0-9a-z]");

    /**
     * 存储配置
     */
    private final StorageProperties properties;

    /**
     * 规范化后的根目录（绝对路径）
     */
    private final Path root;

    /**
     * 构造存储：创建根目录（不存在即建），配置非法启动失败。
     *
     * @param properties 存储配置
     */
    public LocalDiskStorage(StorageProperties properties) {
        this.properties = properties;
        this.root = Path.of(properties.rootDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        }
        catch (IOException e) {
            throw new IllegalStateException("存储根目录创建失败: " + root, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String put(InputStream stream, String contentType) {
        final String normalizedType = normalizeContentType(contentType);
        final byte[] bytes = readLimited(stream, normalizedType);
        final String key = buildKey(normalizedType);
        final Path target = resolveKey(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
        catch (IOException e) {
            throw new IllegalStateException("文件写入失败: " + key, e);
        }
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream get(String objectKey) {
        final Path target = resolveKey(objectKey);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException(EcommerceBusinessCode.STORAGE_FILE_NOT_FOUND.code(), "文件不存在");
        }
        try {
            return Files.newInputStream(target);
        }
        catch (IOException e) {
            throw new IllegalStateException("文件读取失败: " + objectKey, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(String objectKey) {
        final Path target = resolveKey(objectKey);
        try {
            Files.deleteIfExists(target);
        }
        catch (IOException e) {
            throw new IllegalStateException("文件删除失败: " + objectKey, e);
        }
    }

    /**
     * 校验 contentType 白名单并统一小写。
     *
     * @param contentType MIME 类型，可为 null
     * @return 小写化后的类型
     */
    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(EcommerceBusinessCode.STORAGE_CONTENT_TYPE_NOT_ALLOWED.code(), "contentType 缺失");
        }
        final String normalized = contentType.toLowerCase();
        if (!properties.allowedContentTypes().contains(normalized)) {
            throw new BusinessException(EcommerceBusinessCode.STORAGE_CONTENT_TYPE_NOT_ALLOWED.code(), "contentType 不在白名单");
        }
        return normalized;
    }

    /**
     * 读取流内容：空流拒绝；超出上限拒绝（不落盘，无半写文件）。
     *
     * @param stream        内容流
     * @param contentType   已校验的 MIME 类型（仅用于异常消息）
     * @return 内容字节
     */
    private byte[] readLimited(InputStream stream, String contentType) {
        try {
            // 多读 1 字节以检测超限；long 预算防 maxSizeBytes 接近 int 上限时 +1 溢出
            final long readLimit = Math.min(properties.maxSizeBytes() + 1L, Integer.MAX_VALUE);
            final byte[] bytes = stream.readNBytes((int) readLimit);
            if (bytes.length == 0) {
                throw new BusinessException(EcommerceBusinessCode.STORAGE_FILE_EMPTY.code(), "文件内容为空");
            }
            if (bytes.length > properties.maxSizeBytes()) {
                throw new BusinessException(EcommerceBusinessCode.STORAGE_FILE_TOO_LARGE.code(), "文件超出大小上限");
            }
            return bytes;
        }
        catch (IOException e) {
            throw new IllegalStateException("读取上传内容失败", e);
        }
    }

    /**
     * 生成 objectKey：日期目录 + snowflake + 类型扩展名（全系统生成，不经用户输入）。
     *
     * @param contentType 已校验的 MIME 类型
     * @return objectKey（如 2026/08/18/12345.png）
     */
    private String buildKey(String contentType) {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + com.nona.util.IDUtils.generateID() + extensionFor(contentType);
    }

    /**
     * 解析扩展名：已知映射优先，未知（管理员自定义白名单类型）由 MIME subtype
     * 派生出合法字符集的扩展名。
     *
     * @param contentType 小写化 MIME 类型
     * @return 扩展名（含前导点）
     */
    private String extensionFor(String contentType) {
        final String known = EXTENSION_BY_TYPE.get(contentType);
        if (known != null) {
            return known;
        }
        final String subtype = contentType.substring(contentType.indexOf('/') + 1);
        final String cleaned = EXT_SANITIZE.matcher(subtype).replaceAll("");
        if (cleaned.isEmpty()) {
            throw new BusinessException(EcommerceBusinessCode.STORAGE_CONTENT_TYPE_NOT_ALLOWED.code(),
                    "contentType 无法映射扩展名");
        }
        return "." + cleaned;
    }

    /**
     * 校验 objectKey 并解析为目标路径（路径穿越防御核心，put/get/delete 共用）。
     * <p>
     * 防线：分段白名单字符校验 → 拒绝空段/{@code .}/{@code ..} → 拒绝绝对路径
     * （首段空）→ normalize 后必须仍在根目录内（兜底）。
     *
     * @param objectKey 对象键
     * @return 规范化后的目标路径
     */
    private Path resolveKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw invalidKey();
        }
        if (objectKey.length() > MAX_KEY_LENGTH) {
            throw invalidKey();
        }
        final String[] segments = objectKey.split("/");
        for (int i = 0; i < segments.length; i++) {
            final String segment = segments[i];
            if (i == 0 && segment.isEmpty()) {
                throw invalidKey();
            }
            if (!KEY_SEGMENT.matcher(segment).matches()) {
                throw invalidKey();
            }
        }
        final Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw invalidKey();
        }
        return resolved;
    }

    /**
     * 构造非法 key 业务异常。
     *
     * @return 业务异常（400 storage.object_key_invalid）
     */
    private BusinessException invalidKey() {
        return new BusinessException(EcommerceBusinessCode.STORAGE_OBJECT_KEY_INVALID.code(), "objectKey 非法");
    }
}