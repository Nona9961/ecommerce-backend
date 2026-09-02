package com.nona.web;

import com.nona.api.auth.Portal;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * multipart 系统上限兜底集成测试：上传超过 Spring 容器 multipart 上限的文件，
 * 触发 {@code MaxUploadSizeExceededException}，验证全局异常处理返回 400 +
 * {@code storage.file_too_large}（业务上限校验前的最后防线语义）。
 * <p>
 * 独立测试类承载容器级 multipart 配置（max-file-size=1KB），避免影响
 * {@link FileApiIntegrationTest} 的业务层上限用例。
 *
 * @author nona9961
 */
@SpringBootTest(properties = {
        "management.health.redis.enabled=false",
        "nona.storage.root-dir=target/test-storage",
        "nona.storage.max-size-bytes=2048",
        "spring.servlet.multipart.max-file-size=1KB",
        "spring.servlet.multipart.max-request-size=2KB"
})
@AutoConfigureMockMvc
class FileApiMultipartLimitIntegrationTest {

    /**
     * 上传账号 ID（任何登录账号均可上传，角色不限）。
     */
    private static final long UPLOADER_UID = 72001L;

    /**
     * 超过容器上限（1KB）但低于业务上限（2048）的文件内容。
     */
    private static final byte[] OVER_MULTIPART_LIMIT_BYTES =
            "fake-png-content".repeat(150).getBytes(StandardCharsets.UTF_8);

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
     * error：文件超过容器 multipart 上限（1KB）→ 400 + storage.file_too_large
     * （业务上限校验之前的最后防线语义验证）。
     */
    @Test
    @DisplayName("超容器 multipart 上限返回 400 file_too_large")
    void upload_overMultipartLimit_returns400FileTooLarge() throws Exception {
        when(authUserCache.get(anyLong())).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));

        mockMvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "a.png", "image/png", OVER_MULTIPART_LIMIT_BYTES))
                        .header("Authorization", bearer(UPLOADER_UID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("storage.file_too_large"));
    }

    /**
     * 构造登录令牌（MALL 门户即可，上传不区分角色）。
     *
     * @param uid 账号 ID
     * @return Bearer 令牌
     */
    private String bearer(long uid) {
        return "Bearer " + tokenProvider.issueToken(uid, Portal.MALL);
    }
}