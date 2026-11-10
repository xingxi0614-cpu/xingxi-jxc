package com.group.scm.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析工具。
 *
 * <p>Bug #4 修复：系统部署在 Nginx 反向代理之后，{@code request.getRemoteAddr()}
 * 永远返回回环地址 127.0.0.1，导致操作日志、在线用户、最近登录 IP 全部失真。</p>
 *
 * <p>Nginx 已透传 {@code X-Real-IP} 与 {@code X-Forwarded-For}，这里优先读取它们；
 * {@code X-Forwarded-For} 链路格式为「客户端, 代理1, 代理2」，取第一个非空地址即真实来源；
 * 都不存在时（如本机直连、未经代理）回退到 {@code getRemoteAddr()}。</p>
 */
public final class IpUtil {

    private IpUtil() {
    }

    public static String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String realIp = req.getHeader("X-Real-IP");
        if (notBlankUnknown(realIp)) {
            return realIp.trim();
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String part : forwarded.split(",")) {
                String ip = part.trim();
                if (!ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }
        return req.getRemoteAddr();
    }

    private static boolean notBlankUnknown(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value.trim());
    }
}
