package com.nona.inf.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nona.api.HttpResponse;
import com.nona.exceptions.EcommerceBusinessCode;
import com.nona.util.JacksonUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 过滤器链：无状态 JWT 认证 + 门户角色路由。
 * <p>
 * 路由语义：{@code /mall/**}→BUYER、{@code /seller/**}→SELLER、{@code /admin/**}→ADMIN；
 * 未认证统一 401（{@code auth.unauthorized}），已认证但角色不匹配（含封禁拦截）统一 403
 * （{@code auth.forbidden}）；登录/注册/健康检查等公开路径放行；{@code GET /files/**}
 * 公开读取（商品图买家端展示，URL 统一形态；上传/删除仍要求登录）；{@code /internal/**}
 * permitAll（mock 网关回调送达入口，无身份校验语义——网关/系统触发；<b>mock/演示专用</b>，
 * 接真实渠道时本放行项随回调端点一并移除）。
 * 账号状态 SPI（AccountStatusProvider）由身份域 JPA 实现直接注入过滤器
 * （ObjectProvider 懒取已随认证实现落地移除，改为直接注入）。
 *
 * @author nona9961
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * JSON 序列化器（项目统一静态实例）
     */
    private static final ObjectMapper OBJECT_MAPPER = JacksonUtil.DEFAULT_MAPPER;

    /**
     * JWT 解析器
     */
    private final JwtTokenProvider tokenProvider;

    /**
     * 用户上下文缓存
     */
    private final AuthUserCache userCache;

    /**
     * 账号状态 DB SPI（身份域 JPA 实现，直接注入过滤器）
     */
    private final AccountStatusProvider accountStatusProvider;

    /**
     * 构建安全过滤器链：无状态、关闭 CSRF、公开路径放行、门户角色路由、
     * 401/403 统一响应、JWT 过滤器前置注册。
     *
     * @param http 安全构建器
     * @return 安全过滤器链
     * @throws Exception 构建失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/auth/login", "/auth/register",
                                "/actuator/health", "/actuator/info", "/h2/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/files/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/mall/**").hasRole(AuthRole.BUYER.name())
                        .requestMatchers("/seller/**").hasRole(AuthRole.SELLER.name())
                        .requestMatchers("/admin/**").hasRole(AuthRole.ADMIN.name())
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        EcommerceBusinessCode.AUTH_UNAUTHORIZED, "unauthorized"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        EcommerceBusinessCode.AUTH_FORBIDDEN, "forbidden")))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, userCache, accountStatusProvider),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 输出统一错误响应体（HTTP 状态码 + HttpResponse 业务码）。
     *
     * @param response     响应
     * @param status       HTTP 状态码
     * @param businessCode 业务码（ecommerce 域码，如 {@code auth.unauthorized}）
     * @param message      失败提示
     * @throws IOException IO 异常
     */
    private static void writeError(HttpServletResponse response, int status,
                                   EcommerceBusinessCode businessCode, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(
                new HttpResponse<>(businessCode.code(), message, false, null)));
    }
}