package com.group.scm.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.io.IOException;

/**
 * 安全响应头过滤器。
 *
 * <p>在 Nginx 反向代理之后作为第二层防护（Defense in Depth）。
 * 当本机调试不使用 Nginx 时，此过滤器确保安全头仍然存在。</p>
 *
 * <p>Docker 安装自带的 Nginx（deploy/nginx.conf）已配置相同的头，
 * 此过滤器作为补充保证最小安全基线。</p>
 */
@Configuration
public class SecurityHeadersConfig {

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(-200); // 在所有业务 filter 之前执行
        reg.setName("securityHeadersFilter");
        return reg;
    }

    /**
     * 最小安全基线头。
     *
     * <p>注意：</p>
     * <ul>
     *   <li>{@code Content-Security-Policy} 中 style-src 'unsafe-inline' 是 Element Plus 所必需的</li>
     *   <li>{@code frame-ancestors 'self'} 防止点击劫持（等同 X-Frame-Options）</li>
     *   <li>生产环境 Nginx 会覆盖这些值，此过滤器仅作为兜底</li>
     * </ul>
     */
    public static class SecurityHeadersFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletResponse httpResp = (HttpServletResponse) response;
            httpResp.setHeader("X-Content-Type-Options", "nosniff");
            httpResp.setHeader("X-Frame-Options", "SAMEORIGIN");
            httpResp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            // 基础 CSP — 不做过度限制，避免误伤
            httpResp.setHeader("Content-Security-Policy",
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: blob:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self'; " +
                    "object-src 'none'; " +
                    "base-uri 'self'; " +
                    "frame-ancestors 'self'; " +
                    "form-action 'self'");
            chain.doFilter(request, response);
        }
    }
}
