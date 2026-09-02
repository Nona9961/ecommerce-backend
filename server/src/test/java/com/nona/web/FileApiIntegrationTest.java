package com.nona.web;

import com.nona.api.auth.Portal;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件上传/读取/删除 REST 端点集成测试（真实 Security 链 + JWT + 本地磁盘存储）。
 * <p>
 * 覆盖：happy（登录上传返回统一 URL / 公开读回字节与 Content-Type / 登录删除）、
 * critical（恰好/超限、空文件）、error（未登录 401、白名单拒绝、超限拒绝、
 * 不存在 404、路径穿越拒绝）。上传端点登录即可（不区分门户），读取路由公开
 * （商品图在买家端展示，URL 统一形态 {@code /files/{objectKey}}）。
 * 存储根目录与大小上限经测试配置覆盖（target/test-storage，避免污染工作区）。
 */
@SpringBootTest(properties = {
        "management.health.redis.enabled=false",
        "nona.storage.root-dir=target/test-storage",
        "nona.storage.max-size-bytes=2048"
})
@AutoConfigureMockMvc
class FileApiIntegrationTest {

    /**
     * 上传账号 ID（任何登录账号均可上传，角色不限）。
     */
    private static final long UPLOADER_UID = 71001L;

    /**
     * key 形态：yyyy/MM/dd/snowflake.png。
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^/files/\\d{4}/\\d{2}/\\d{2}/\\d+\\.png$");

    /**
     * 合法 PNG 内容（任意字节，MockMvc 不校验图片真实性）。
     */
    private static final byte[] PNG_BYTES = "fake-png-content".getBytes(StandardCharsets.UTF_8);

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * JWT 签发器（构造合法 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis 并注入登录账号）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前：stub 登录账号上下文、清空测试存储目录。
     */
    @BeforeEach
    void setUp() {
        when(authUserCache.get(anyLong())).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));
        clearDir(Path.of("target/test-storage"));
    }

    /**
     * happy：登录后上传 PNG → 200，返回统一 URL 形态 /files/yyyy/MM/dd/id.png。
     */
    @Test
    @DisplayName("登录上传成功：返回 URL 统一形态")
    void upload_withLogin_returnsFileUrl() throws Exception {
        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES))
                        .header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.url").value(matchesPattern(KEY_PATTERN.pattern())));
    }

    /**
     * error：未登录上传 → 401（安全链统一语义）。
     */
    @Test
    @DisplayName("未登录上传拒绝 401")
    void upload_withoutLogin_rejectsUnauthorized() throws Exception {
        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * error：白名单外 contentType 拒绝 400。
     */
    @Test
    @DisplayName("白名单外 contentType 拒绝")
    void upload_disallowedContentType_rejects() throws Exception {
        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "a.svg", "image/svg+xml", PNG_BYTES))
                        .header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("storage.content_type_not_allowed"));
    }

    /**
     * error：超出大小上限（测试配置 2048 字节）拒绝 400 且无残留。
     */
    @Test
    @DisplayName("超出大小上限拒绝")
    void upload_oversize_rejects() throws Exception {
        final byte[] big = new byte[4096];

        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("storage.file_too_large"));
    }

    /**
     * error：空文件拒绝 400。
     */
    @Test
    @DisplayName("空文件拒绝")
    void upload_emptyFile_rejects() throws Exception {
        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("storage.file_empty"));
    }

    /**
     * happy：公开读取（无需登录）返回字节一致 + 正确 Content-Type。
     */
    @Test
    @DisplayName("公开读取：字节一致且 Content-Type 正确")
    void download_existingKey_returnsBytesAndContentType() throws Exception {
        final String key = uploadAndGetKey();

        mockMvc.perform(get(key))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"))
                .andExpect(content().bytes(PNG_BYTES));
    }

    /**
     * error：读取不存在的 key → 404 业务码。
     */
    @Test
    @DisplayName("读取不存在的 key 返回 404")
    void download_missingKey_returns404() throws Exception {
        mockMvc.perform(get("/files/2026/08/18/999999.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("storage.file_not_found"));
    }

    /**
     * error（路径穿越攻击）：读取 ../ 形态 key → 路由/规范化的 4xx 拒绝（框架层防线；
     * 存储层防线在 {@code LocalDiskStorageTest} 直测）。
     */
    @Test
    @DisplayName("路径穿越读取拒绝")
    void download_pathTraversal_rejects() throws Exception {
        mockMvc.perform(get("/files/../pom.xml"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * happy：登录删除成功 → 再读 404（文件确实消失）。
     */
    @Test
    @DisplayName("登录删除成功：删除后读回 404")
    void delete_withLogin_removesFile() throws Exception {
        final String key = uploadAndGetKey();

        mockMvc.perform(delete(key).header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get(key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("storage.file_not_found"));
    }

    /**
     * error：未登录删除 → 401。
     */
    @Test
    @DisplayName("未登录删除拒绝 401")
    void delete_withoutLogin_rejectsUnauthorized() throws Exception {
        mockMvc.perform(delete("/files/2026/08/18/777777.png"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.unauthorized"));
    }

    /**
     * error（路径穿越攻击）：删除 ../ 形态 key → 路由/规范化的 4xx 拒绝（框架层防线）。
     */
    @Test
    @DisplayName("路径穿越删除拒绝")
    void delete_pathTraversal_rejects() throws Exception {
        mockMvc.perform(delete("/files/../pom.xml").header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().is4xxClientError());
    }

    /**
     * 构造合法 Bearer token。
     *
     * @param uid 账号 ID
     * @return Authorization header 值
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.MALL);
    }

    /**
     * 上传一张合法 PNG 并解析返回的 key（含 /files/ 前缀的完整路径）。
     *
     * @return 例如 /files/2026/08/18/123.png
     * @throws Exception MockMvc 执行异常
     */
    private String uploadAndGetKey() throws Exception {
        final MvcResult result = mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES))
                        .header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isOk())
                .andReturn();
        final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        final Matcher match = Pattern.compile("\"url\":\"(/files/[^\"]+)\"").matcher(body);
        if (!match.find()) {
            throw new IllegalStateException("响应未包含 url: " + body);
        }
        return match.group(1);
    }

    /**
     * 递归清空目录内容（保留目录本身）。
     *
     * @param dir 目标目录
     */
    private static void clearDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(dir))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        }
                        catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}