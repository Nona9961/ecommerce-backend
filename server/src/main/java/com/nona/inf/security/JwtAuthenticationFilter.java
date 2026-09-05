package com.nona.inf.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.HttpResponse;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.inf.context.TrackingContext;
import com.nona.inf.context.TrackingScope;
import com.nona.util.JacksonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * JWT 认证过滤器：解析 Bearer token → 取用户上下文（缓存命中直取，
 * miss 走 DB SPI 回填，缓存故障由缓存层降级）→ 封禁拦截 → 组装
 * SecurityContext 与跟踪作用域持有者（含商家店铺上下文写入租户）。
 * <p>
 * 本过滤器只做「认定」与「组装」：token 非法/过期、用户不存在一律不设置认证
 * （沿用链式 401 语义）；封禁（BANNED）属于已认定但被拒的账号，按设计统一 403
 * （{@code auth.forbidden}），由本过滤器直接裁决。账号状态 SPI 由身份域注册的 JPA
 * 实现直接注入（认证实现随本域落地），实现缺失即启动失败（fail-fast）。
 *
 * @author nona9961
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Bearer 前缀（含尾随空格）
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 授权头名称
     */
    private static final String AUTH_HEADER = "Authorization";

    /**
     * JWT 解析器
     */
    private final JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存
     */
    private final AuthUserCache userCache;

    /**
     * DB SPI（身份域 JPA 实现，直接注入）
     */
    private final AccountStatusProvider accountStatusProvider;

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private static final ObjectMapper OBJECT_MAPPER = JacksonUtil.DEFAULT_MAPPER;

    /**
     * 构造过滤器。
     *
     * @param tokenProvider         JWT 解析器
     * @param userCache             用户上下文缓存
     * @param accountStatusProvider 账号状态 DB SPI（身份域 JPA 实现）
     */
    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   AuthUserCache userCache,
                                   AccountStatusProvider accountStatusProvider) {
        this.tokenProvider = tokenProvider;
        this.userCache = userCache;
        this.accountStatusProvider = accountStatusProvider;
    }

    /**
     * 过滤器主逻辑：Bearer token 存在才尝试认证，否则原样放行（由安全链统一裁决 401）。
     *
     * @param request     请求
     * @param response    响应
     * @param filterChain 过滤器链
     * @throws ServletException 链异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        final String authorization = request.getHeader(AUTH_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        final Optional<TokenClaims> claims = tokenProvider.parse(authorization.substring(BEARER_PREFIX.length()));
        if (claims.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        final Long uid = claims.get().uid();
        Optional<AuthUserContext> userContext = userCache.get(uid);
        if (userContext.isEmpty()) {
            userContext = loadFromDbAndBackfill(uid);
        }
        if (userContext.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        final AuthUserContext context = userContext.get();
        if (context.status() == AccountStatus.BANNED) {
            log.info("[auth] banned user rejected uid={} path={}", uid, request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, EcommerceBusinessCode.AUTH_FORBIDDEN, "forbidden");
            return;
        }
        assembleContext(uid, context);
        filterChain.doFilter(request, response);
    }

    /**
     * 缓存 miss 路径：查 DB（SPI）并尽力回填缓存。
     *
     * @param uid 用户 ID
     * @return 用户上下文；DB 无此账号返回空
     */
    private Optional<AuthUserContext> loadFromDbAndBackfill(Long uid) {
        final Optional<AuthUserContext> loaded = accountStatusProvider.loadUserContext(uid);
        loaded.ifPresent(context -> userCache.put(uid, context));
        return loaded;
    }

    /**
     * 组装 Spring Security 认证与跟踪作用域：角色写入 SecurityContext 与
     * 当前跟踪作用域持有者（{@link TrackingContext#scope()}，TrackingFilter
     * 已先行绑定）；商家账号（shopIds 非空）取当前店铺（一期恒 1 个）写入
     * 持有者 tenantID（归属已在登录路径校验，运行期不再校验）。
     *
     * @param uid     用户 ID
     * @param context 用户上下文（角色/店铺列表）
     */
    private void assembleContext(Long uid, AuthUserContext context) {
        final List<GrantedAuthority> authorities = context.roles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        final SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(uid, null, authorities));
        SecurityContextHolder.setContext(securityContext);
        final TrackingScope scope = TrackingContext.scope();
        if (scope != null) {
            scope.setIdentity(uid.toString());
            scope.setRole(List.copyOf(context.roles()));
            if (!context.shopIds().isEmpty()) {
                scope.setTenantID(context.shopIds().get(0).toString());
            }
        }
    }

    /**
     * 输出统一错误响应体（HTTP 状态码 + HttpResponse 业务码）。
     *
     * @param response     响应
     * @param status       HTTP 状态码
     * @param businessCode 业务码（ecommerce 域码，如 {@code auth.forbidden}）
     * @param message      失败提示
     * @throws IOException IO 异常
     */
    private void writeError(HttpServletResponse response, int status,
                            EcommerceBusinessCode businessCode, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                new HttpResponse<>(businessCode.code(), message, false, null)));
    }
}