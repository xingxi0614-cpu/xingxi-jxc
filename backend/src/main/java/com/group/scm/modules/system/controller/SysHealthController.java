package com.group.scm.modules.system.controller;

import com.group.scm.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康检查端点。
 *
 * <p>用于发布后自动确认服务可用性：
 * <ul>
 *   <li>数据库连接状态</li>
 *   <li>应用版本号</li>
 *   <li>服务启动时间与运行时长</li>
 * </ul>
 *
 * <p>云端更新脚本在部署后会调用此接口，
 * 确认全部通过后才认为发布成功。失败则自动停止发布流程。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SysHealthController {

    private final DataSource dataSource;
    /**
     * 构建信息(版本号/构建时间/Git 提交号),来自 jar 里的 META-INF/build-info.properties。
     * ⚠️ 用 ObjectProvider 而不是直接注入:IDE 里直接跑 main 时没有这个文件,
     *    直接注入会让整个应用起不来。取不到就返回 unknown,不影响服务。
     */
    private final org.springframework.beans.factory.ObjectProvider<
            org.springframework.boot.info.BuildProperties> buildProperties;

    @Value("${spring.application.name:scm-backend}")
    private String appName;

    /** 启动时间戳，类加载时记录一次 */
    private final LocalDateTime startedAt = LocalDateTime.now();

    /**
     * 完整健康检查。
     * <p>返回 JSON：
     * <pre>{@code
     * {
     *   "code": 0,
     *   "data": {
     *     "status": "UP",
     *     "app": "scm-backend",
     *     "db": "UP",
     *     "startedAt": "2026-06-03 12:00:00",
     *     "uptime": "2h 13m",
     *     "uptimeSeconds": 7980
     *   }
     * }
     * }</pre>
     */
    @GetMapping("/system/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();

        // 数据库连通性检查
        // 🔴 这个接口是【免登录】的(在 SaTokenConfig 白名单里),任何人都能打。
        //    原来会回显完整 JDBC 地址(库名、端口、连接参数),等于白送内网信息给探测者,
        //    也违反「不泄露内部信息」的原则。健康检查只需要回答"通不通",不需要说"连的是谁"。
        //    (要看连的哪个库,登录后用数据备份页或直接上服务器看配置。)
        String dbStatus = "UP";
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(3)) {
                dbStatus = "DOWN";
            }
        } catch (Exception e) {
            dbStatus = "DOWN";
            log.error("健康检查 - 数据库连接失败", e);
        }

        // 运行时长
        Duration uptime = Duration.between(startedAt, LocalDateTime.now());
        long uptimeSeconds = uptime.getSeconds();
        String uptimeStr;
        if (uptimeSeconds < 120) {
            uptimeStr = uptimeSeconds + "s";
        } else if (uptimeSeconds < 3600) {
            uptimeStr = uptime.toMinutes() + "m " + (uptimeSeconds % 60) + "s";
        } else {
            uptimeStr = uptime.toHours() + "h " + (uptime.toMinutes() % 60) + "m";
        }

        String overallStatus = "UP".equals(dbStatus) ? "UP" : "DOWN";

        info.put("status", overallStatus);
        info.put("app", appName);
        info.put("db", dbStatus);
        info.put("startedAt", startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        info.put("uptime", uptimeStr);
        info.put("uptimeSeconds", uptimeSeconds);

        return Result.ok(info);
    }

    /**
     * 线上跑的到底是哪一次提交。
     *
     * <p>【为什么要有】出问题时第一个要回答的问题就是「线上是哪个版本」。
     * 原来只能靠部署记录和记忆猜:前端 package.json 写 1.4.0、后端 pom 写 1.2.44,
     * 两边对不上,jar 里也没有任何 Git 信息。现在打包时把提交号写进 jar,一查便知。
     *
     * <p>🔴 这个接口【必须登录】才能访问 —— 版本号会暴露"跑的是哪个已知版本",
     * 是给攻击者做靶向的信息。健康检查那个匿名接口只回答"通不通",不带版本。
     */
    @GetMapping("/system/version")
    public Result<Map<String, Object>> version() {
        Map<String, Object> info = new LinkedHashMap<>();
        var bp = buildProperties.getIfAvailable();
        info.put("app", appName);
        info.put("version", bp == null ? "unknown" : bp.getVersion());
        info.put("gitSha", bp == null ? "unknown" : bp.get("git.sha"));
        info.put("buildTime", bp == null || bp.getTime() == null ? "unknown" : bp.getTime().toString());
        info.put("startedAt", startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return Result.ok(info);
    }

    /**
     * 轻量存活检查，不连数据库，仅确认服务进程在运行。
     * <p>Nginx 可用此端点做 upstream 健康探测。</p>
     */
    @GetMapping("/system/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("app", appName);
        return Result.ok(info);
    }
}
