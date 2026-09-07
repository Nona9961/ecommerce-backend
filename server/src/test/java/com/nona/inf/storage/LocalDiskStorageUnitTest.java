package com.nona.inf.storage;

import com.nona.exceptions.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LocalDiskStorage} 单元测试：put/get/delete 全场景 + 路径穿越攻击防御。
 * <p>
 * 覆盖：happy（上传落盘/读回/删除）、critical（恰好等于大小上限、空流）、
 * error（白名单拒绝、超限、objectKey 非法——含 {@code ../}、绝对路径、
 * 非法字符、空值等路径穿越用例）。根目录使用 JUnit 临时目录，零外部依赖。
 */
class LocalDiskStorageUnitTest {

    /**
     * 默认白名单（与生产默认配置一致的 4 类电商图片类型）。
     */
    private static final List<String> DEFAULT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp", "image/gif");

    /**
     * 单元测试大小上限（字节）。
     */
    private static final int MAX_SIZE = 1024;

    /**
     * 临时根目录（JUnit 自动清理）。
     */
    @TempDir
    Path tempDir;

    /**
     * 被测存储（每用例重建，隔离状态）。
     */
    private LocalDiskStorage storage;

    /**
     * 每用例前基于临时目录构造新实例。
     */
    @BeforeEach
    void setUp() {
        storage = new LocalDiskStorage(new StorageProperties(tempDir.toString(), MAX_SIZE, DEFAULT_TYPES));
    }

    /**
     * happy：上传 PNG 流 → 返回嵌套日期目录 key，落盘内容一致。
     */
    @Test
    @DisplayName("上传成功：key 形如 yyyy/MM/dd/id.png 且落盘内容一致")
    void put_pngStream_returnsNestedKeyAndWritesContent() throws Exception {
        final byte[] bytes = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

        final String key = storage.put(new ByteArrayInputStream(bytes), "image/png");

        final String expectedPrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        assertThat(key).startsWith(expectedPrefix + "/").endsWith(".png");
        final Path file = tempDir.resolve(key);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
    }

    /**
     * happy：不同白名单类型映射各自扩展名。
     */
    @Test
    @DisplayName("上传成功：jpeg/webp/gif 映射 .jpg/.webp/.gif 扩展名")
    void put_otherAllowedTypes_mapToExpectedExtensions() {
        assertThat(storage.put(new ByteArrayInputStream(new byte[]{1}), "image/jpeg")).endsWith(".jpg");
        assertThat(storage.put(new ByteArrayInputStream(new byte[]{1}), "image/webp")).endsWith(".webp");
        assertThat(storage.put(new ByteArrayInputStream(new byte[]{1}), "image/gif")).endsWith(".gif");
    }

    /**
     * critical：contentType 大小写不敏感（RFC 6838 MIME 大小写无差异）。
     */
    @Test
    @DisplayName("上传成功：contentType 大小写不敏感")
    void put_contentTypeCaseInsensitive_accepts() {
        final String key = storage.put(new ByteArrayInputStream(new byte[]{1}), "image/PNG");

        assertThat(key).endsWith(".png");
    }

    /**
     * error：白名单外 contentType 拒绝。
     */
    @Test
    @DisplayName("白名单外 contentType 拒绝")
    void put_unknownContentType_rejects() {
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(new byte[]{1}), "image/svg+xml"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getBusinessCode()).isEqualTo("storage.content_type_not_allowed");
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                });
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(new byte[]{1}), "application/octet-stream"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：null/空白 contentType 拒绝。
     */
    @Test
    @DisplayName("null/空白 contentType 拒绝")
    void put_blankContentType_rejects() {
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(new byte[]{1}), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(new byte[]{1}), "  "))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * error：超出大小上限拒绝，且不残留半写文件。
     */
    @Test
    @DisplayName("超出大小上限拒绝且无残留文件")
    void put_oversize_rejectsAndLeavesNoFile() throws IOException {
        final byte[] bytes = new byte[MAX_SIZE + 1];

        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(bytes), "image/png"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getBusinessCode()).isEqualTo("storage.file_too_large");
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                });
        assertThat(noFilesUnderRoot()).isTrue();
    }

    /**
     * critical：恰好等于大小上限可成功。
     */
    @Test
    @DisplayName("恰好等于大小上限可成功")
    void put_exactlyMaxSize_succeeds() {
        final byte[] bytes = new byte[MAX_SIZE];

        final String key = storage.put(new ByteArrayInputStream(bytes), "image/png");

        assertThat(Files.exists(tempDir.resolve(key))).isTrue();
    }

    /**
     * error：空流拒绝。
     */
    @Test
    @DisplayName("空流拒绝")
    void put_emptyStream_rejects() {
        assertThatThrownBy(() -> storage.put(new ByteArrayInputStream(new byte[0]), "image/png"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getBusinessCode()).isEqualTo("storage.file_empty");
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                });
    }

    /**
     * happy：读回已上传内容字节一致。
     */
    @Test
    @DisplayName("读回已上传内容字节一致")
    void get_existingKey_returnsSameBytes() throws Exception {
        final byte[] bytes = "round-trip-bytes".getBytes(StandardCharsets.UTF_8);
        final String key = storage.put(new ByteArrayInputStream(bytes), "image/png");

        try (InputStream in = storage.get(key)) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    /**
     * error：读取不存在的 key → 404 业务码。
     */
    @Test
    @DisplayName("读取不存在的 key 报错")
    void get_missingKey_throwsNotFound() {
        assertThatThrownBy(() -> storage.get("2026/08/18/999999.png"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getBusinessCode()).isEqualTo("storage.file_not_found");
                    assertThat(e.getHttpStatus()).isEqualTo(404);
                });
    }

    /**
     * critical：多段合法 key（日期目录）可解析，仅因文件不存在报 404，而非非法拒绝。
     */
    @Test
    @DisplayName("多段合法 key 不触发非法拒绝")
    void get_multilevelKey_notRejectedAsInvalid() {
        assertThatThrownBy(() -> storage.get("2026/08/18/123.jpg"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getBusinessCode()).isEqualTo("storage.file_not_found"));
    }

    /**
     * error（路径穿越攻击）：null、空白、../、绝对路径、非法字符、隐藏文件等一律拒绝。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "../secret.png",
            "a/../../secret.png",
            "..",
            "/etc/passwd",
            "C:/secret.png",
            "a#b.png",
            "a b.png",
            ".hidden.png",
            "./a.png",
            "a//b.png",
            "a/%2e%2e/b.png",
            "~user/a.png",
            ""
    })
    @DisplayName("非法 objectKey 拒绝（路径穿越/绝对路径/非法字符）")
    void get_invalidKey_rejects(String key) {
        assertThatThrownBy(() -> storage.get(key))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getBusinessCode()).isEqualTo("storage.object_key_invalid");
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                });
    }

    /**
     * critical：超长 objectKey（256 上限）拒绝——防御无意义深层路径的 DoS 面。
     */
    @Test
    @DisplayName("超长 objectKey 拒绝")
    void get_overlongKey_rejects() {
        final String overlong = "a/".repeat(300) + "b.png";
        assertThatThrownBy(() -> storage.get(overlong))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getBusinessCode()).isEqualTo("storage.object_key_invalid"));
    }

    /**
     * error：非法配置启动失败（maxSizeBytes 非正数 / 超出 int 上限读闭区间）。
     */
    @Test
    @DisplayName("非法 maxSizeBytes 配置拒绝构造")
    void constructor_invalidMaxSize_rejects() {
        assertThatThrownBy(() -> new StorageProperties(tempDir.toString(), 0, DEFAULT_TYPES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StorageProperties(tempDir.toString(), Integer.MAX_VALUE + 1L, DEFAULT_TYPES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * error：null objectKey 拒绝。
     */
    @Test
    @DisplayName("null objectKey 拒绝")
    void get_nullKey_rejects() {
        assertThatThrownBy(() -> storage.get(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getBusinessCode()).isEqualTo("storage.object_key_invalid"));
    }

    /**
     * happy：删除已上传文件 → 文件消失且读回 404。
     */
    @Test
    @DisplayName("删除成功：文件消失、读回 404")
    void delete_existingKey_removesFile() {
        final String key = storage.put(new ByteArrayInputStream(new byte[]{1}), "image/png");

        storage.delete(key);

        assertThat(Files.exists(tempDir.resolve(key))).isFalse();
        assertThatThrownBy(() -> storage.get(key))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getBusinessCode()).isEqualTo("storage.file_not_found"));
    }

    /**
     * critical：删除不存在的 key 幂等成功（对象存储语义，不报错）。
     */
    @Test
    @DisplayName("删除不存在的 key 幂等成功")
    void delete_missingKey_isIdempotent() {
        assertThatCode(() -> storage.delete("2026/08/18/888888.png")).doesNotThrowAnyException();
    }

    /**
     * error（路径穿越攻击）：delete 与 get 同一校验防线。
     */
    @ParameterizedTest
    @ValueSource(strings = {"../secret.png", "/etc/passwd", "a#b.png", "a/../../b.png"})
    @DisplayName("删除非法 objectKey 拒绝")
    void delete_invalidKey_rejects(String key) {
        assertThatThrownBy(() -> storage.delete(key))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getBusinessCode()).isEqualTo("storage.object_key_invalid"));
    }

    /**
     * 断言根目录下无任何文件（超限拒绝后不残留半写文件的证据）。
     *
     * @return 根目录为空返回 true
     * @throws IOException 遍历失败
     */
    private boolean noFilesUnderRoot() throws IOException {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            return walk.filter(Files::isRegularFile).findAny().isEmpty();
        }
    }
}