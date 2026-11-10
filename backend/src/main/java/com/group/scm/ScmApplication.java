package com.group.scm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 星嘻进销存 · 启动类
 *
 * <p>访问地址：</p>
 * <ul>
 *   <li>API 文档：http://localhost:8080/api/doc.html</li>
 *   <li>健康检查：http://localhost:8080/api/auth/ping</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan("com.group.scm.modules.**.mapper")
@EnableTransactionManagement
@EnableScheduling
@EnableAsync
public class ScmApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ScmApplication.class, args);
        String port = context.getEnvironment().getProperty("server.port", "8080");
        String contextPath = context.getEnvironment().getProperty("server.servlet.context-path", "");
        boolean docsEnabled = Boolean.parseBoolean(context.getEnvironment().getProperty("knife4j.enable", "false"));
        System.out.println("\n  =====================================");
        System.out.println("    星嘻进销存 后端已启动");
        System.out.println("    API:  http://localhost:" + port + contextPath);
        if (docsEnabled) {
            System.out.println("    Docs: http://localhost:" + port + contextPath + "/doc.html");
        } else {
            System.out.println("    Docs: disabled (set KNIFE4J_ENABLE=true for local debugging)");
        }
        System.out.println("  =====================================\n");
    }
}
