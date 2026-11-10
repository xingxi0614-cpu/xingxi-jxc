package com.group.scm.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.group.scm.modules.system.service.OnlineSessionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 配置：
 * <ul>
 *   <li>所有接口默认要求登录</li>
 *   <li>白名单：登录、健康检查、一线免登录提交等</li>
 *   <li>接口文档(doc.html / swagger-ui / v3/api-docs / webjars)2026-10-18 起全环境关闭并移出白名单:
 *       功能关了还留着免登录口子没有意义,扫描器探测这些地址现在和其它地址一样先回 4001</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final OnlineSessionInterceptor onlineSessionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/captcha",
                        "/auth/ping",
                        "/lr-public/**", "/so-public/**",
                        "/system/health",
                        "/system/ping",
                        "/favicon.ico"
                );
        registry.addInterceptor(onlineSessionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // ⚠️ 这两个是页面后台自动轮询的"纯展示"接口（在线人数 15 秒 / 未读通知 30 秒），
                        //    人根本没操作。让它们刷新 lastActiveAt 会把三道防线一起顶废：
                        //    ①Sa-Token active-timeout=30 分钟 ②后端 cleanupIdleUsers 定时强制踢下线
                        //    ③在线名额按最早活跃挤人（僵尸会话永远看起来很活跃）。
                        //    已查实并修复；新增轮询接口一律加进这里。
                        "/sys/online-users/summary",
                        "/notif/unread-count",
                        "/auth/login",
                        "/auth/captcha",
                        "/auth/ping",
                        "/lr-public/**", "/so-public/**",
                        "/system/health",
                        "/system/ping",
                        "/favicon.ico"
                );
    }
}
