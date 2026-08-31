package com.nona.application.advice;

import com.nona.api.auth.Portal;
import com.nona.exceptions.BusinessException;
import com.nona.inf.security.AccountStatus;
import com.nona.inf.security.AuthUserCache;
import com.nona.inf.security.AuthUserContext;
import com.nona.inf.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器 HTTP 语义契约测试：真实 Security 链 + {@link ExceptionAdviser}。
 * <p>
 * 无真实 Redis（用户上下文缓存以 mock 替代）；合法 token 由真实
 * {@link JwtTokenProvider} 签发（认证通过后异常才进入 adviser，而非安全链拦截）。
 * 覆盖：未匹配路径 404、兜底 500（消息不泄露内部细节）、业务异常显式状态透传。
 */
@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
class ExceptionAdviserHttpSemanticsTest {

    /**
     * 活跃买家 uid（探测端点鉴权用）
     */
    private static final long BUYER_UID = 9001L;

    /**
     * MockMvc 客户端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * JWT 签发器（测试直接签发合法 token）
     */
    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存（mock，隔离真实 Redis）
     */
    @MockitoBean
    private AuthUserCache authUserCache;

    /**
     * 每用例前 stub 缓存命中：活跃买家上下文（走真实过滤器组装）。
     */
    @BeforeEach
    void setUp() {
        when(authUserCache.get(anyLong())).thenReturn(Optional.of(
                new AuthUserContext(AccountStatus.ACTIVE, List.of("BUYER"), List.of())));
    }

    /**
     * 未匹配路径：认证通过后 MVC 无 handler → 404 + generic.not_found（非安全链 401 拦截语义）。
     */
    @Test
    void unmatchedPath_withValidToken_returns404NotFound() throws Exception {
        final String token = tokenProvider.issueToken(BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/mall/no-such-endpoint-xyz").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("generic.not_found"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 兜底 500：控制器抛 RuntimeException → generic.internal_error，内部消息不泄露。
     */
    @Test
    void runtimeException_fromController_returns500InternalError() throws Exception {
        final String token = tokenProvider.issueToken(BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe-runtime-error").header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("generic.internal_error"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", not(containsString("probe boom"))));
    }

    /**
     * 业务异常显式状态透传：显式 409 优先于域码默认映射（catalog 段默认 404）。
     */
    @Test
    void businessException_explicitStatus_returns409WithCode() throws Exception {
        final String token = tokenProvider.issueToken(BUYER_UID, Portal.MALL);

        mockMvc.perform(get("/mall/probe-business-conflict").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("catalog.stock_insufficient"))
                .andExpect(jsonPath("$.message").value("库存不足"))
                .andExpect(jsonPath("$.success").value(false));
    }
}

/**
 * 异常处理契约探测端点（测试支撑）：抛出各类异常供 adviser 断言。
 * 仅存在于测试 classpath，不进入生产制品。
 *
 * @author nona9961
 */
@RestController
class ErrorProbeController {

    /**
     * 抛运行时异常（adviser 兜底路径）。
     */
    @GetMapping("/mall/probe-runtime-error")
    public void runtimeError() {
        throw new RuntimeException("probe boom");
    }

    /**
     * 抛业务异常（显式状态优先路径）。
     */
    @GetMapping("/mall/probe-business-conflict")
    public void businessConflict() {
        throw new BusinessException("catalog.stock_insufficient", "库存不足", 409);
    }
}
