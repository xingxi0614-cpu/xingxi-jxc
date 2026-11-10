package com.group.scm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS 跨域配置：使用 CorsFilter（Servlet 过滤器级别），
 * 保证在 Sa-Token 拦截器之前处理 OPTIONS 预检请求，
 * 避免预检被拦截返回 500。
 * 生产环境请将 allowedOriginPatterns 限制为实际前端域名。
 */
@Configuration
public class CorsConfig {

    @Value("${scm.cors.allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        for (String pattern : allowedOriginPatterns.split(",")) {
            if (!pattern.isBlank()) config.addAllowedOriginPattern(pattern.trim());
        }
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.addExposedHeader("Authorization");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
