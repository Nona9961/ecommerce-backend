package com.nona.inf.context;

import com.nona.annotation.ScaffoldGenerated;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 请求入口过滤器：以 {@link TrackingContext#withScope(Runnable)} 包裹整个请求链路。
 * <p>
 * <strong>职责</strong>：
 * <ol>
 *   <li>任何请求（含无 DB 访问路径）先绑定空 {@link TrackingScope} 持有者——作用域内
 *       {@link TrackingContext#tracker} 懒创建、持有者三元组经消费者授权过滤器写入；
 *       随后业务过滤器的 tenant 读取与 {@code DifferRepository} 追踪均落在同一作用域内</li>
 *   <li>作用域退出（包含异常路径）自动恢复 unbound（JEP 506 词法作用域语义）——
 *       池化线程复用无残留，无需手动清理</li>
 * </ol>
 * <strong>顺序</strong>：{@link Ordered#HIGHEST_PRECEDENCE}——先于消费者授权过滤器等业务
 * 过滤器绑定作用域（写入持有者的前提是运行在 {@code withScope} 内）。契约只要求
 * 「先于业务过滤器」，不锁定具体数值；若消费者过滤器注册顺序或 security filter
 * chain 的编排变化，可复核调整该数值。
 * <p>
 * fail-closed 对照：不经过本过滤器（或异步传播装饰器）绑定的线程，调用
 * {@link TrackingContext#tracker} 抛 {@link IllegalStateException}——语义由
 * {@link TrackingContext} 保证，本过滤器只负责「绑定」。
 *
 * @author nona9961
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ScaffoldGenerated
public class TrackingFilter extends OncePerRequestFilter {

    /**
     * 以 {@code TrackingContext.withScope(() -> filterChain.doFilter(request, response))}
     * 包裹整个过滤链——请求先绑定空 holder，链路退出（含异常）自动 unbound。
     * <p>
     * <strong>异常形状</strong>：{@code filterChain.doFilter} 抛 {@link ServletException} /
     * {@link IOException}（受检），而 {@link TrackingContext#withScope(Runnable)} 接受
     * {@link Runnable}（不声明受检异常）——链路受检异常先包入私有
     * {@link UncheckedChainException}（作用域内原样穿过、自动恢复 unbound），
     * 本方法出口按原始类型解包原样传播；链路内的运行时异常不经包装、原样传播
     * （实例身份保持一致）。
     *
     * @param request     当前 HTTP 请求
     * @param response    当前 HTTP 响应
     * @param filterChain 剩余过滤链；在 {@code TrackingContext.withScope} 作用域内执行
     * @throws ServletException 链路内的 Servlet 异常（原样传播，作用域自动退出）
     * @throws IOException      链路内的 IO 异常（原样传播，作用域自动退出）
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            TrackingContext.withScope(() -> {
                try {
                    filterChain.doFilter(request, response);
                } catch (ServletException | IOException e) {
                    throw new UncheckedChainException(e);
                }
            });
        } catch (UncheckedChainException e) {
            e.rethrow();
        }
    }

    /**
     * 链路受检异常（{@link ServletException} / {@link IOException}）的作用域内传递载体：
     * 使受检异常能以运行时异常穿过 {@link Runnable}，出作用域后在
     * {@link #doFilterInternal} 出口按原始类型解包——作用域绑定与异常传播互不干扰。
     */
    private static final class UncheckedChainException extends RuntimeException {

        private UncheckedChainException(Throwable cause) {
            super(cause);
        }

        /**
         * 按原始类型重新抛出包装的链路异常。
         *
         * @throws ServletException 原始链路异常为 ServletException 时
         * @throws IOException      原始链路异常为 IOException 时
         */
        private void rethrow() throws ServletException, IOException {
            final Throwable cause = getCause();
            if (cause instanceof ServletException servletException) {
                throw servletException;
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IllegalStateException("Unexpected checked exception type from filter chain", cause);
        }
    }
}