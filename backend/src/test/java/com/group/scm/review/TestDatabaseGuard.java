package com.group.scm.review;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reject an accidentally pointed shared/production DB before destructive real-DB fixtures run. */
final class TestDatabaseGuard {
    private TestDatabaseGuard() {}

    static void requireEnvironment() {
        requireSafeUrl(System.getenv("SPRING_DATASOURCE_URL"), System.getenv());
    }

    static void requireSafeUrl(String jdbcUrl, Map<String, String> env) {
        URI uri;
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            uri = URI.create(jdbcUrl.substring(5));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Real-DB regression requires an explicit local MySQL test URL", ex);
        }
        String db = uri.getPath();
        int port = uri.getPort();
        boolean loopback = "127.0.0.1".equals(uri.getHost()) && uri.getUserInfo() == null && uri.getFragment() == null;
        Map<String, String> expectedOptions = Map.of("useUnicode", "true", "characterEncoding", "UTF-8",
                "serverTimezone", "Asia/Shanghai", "allowPublicKeyRetrieval", "true", "useSSL", "false");
        boolean safeOptions = true;
        if (uri.getRawQuery() != null) {
            for (String option : uri.getRawQuery().split("&", -1)) {
                String[] pair = option.split("=", -1);
                if (pair.length != 2 || !pair[1].equals(expectedOptions.get(pair[0]))) safeOptions = false;
            }
        }
        boolean ci = "true".equalsIgnoreCase(env.get("GITHUB_ACTIONS"));
        boolean ciFixture = ci && port == 3306 && "/scm".equals(db);
        String project = env.get("E2E_PROJECT");
        String verifiedPort = env.get("SCM_DB_TEST_VERIFIED_PORT");
        boolean dedicatedFixture = project != null && project.matches("oss-e2e(?:-[a-z0-9-]+)?")
                && port > 1024 && port <= 65535 && port != 3306 && "/scm_regression".equals(db)
                && Integer.toString(port).equals(verifiedPort);
        // Windows preflight starts a throwaway mysqld under a timestamped temp directory.
        String temporaryDir = env.get("SCM_DB_TEST_TEMP_DIR");
        boolean windowsFixture = false;
        if (temporaryDir != null && port > 1024 && port <= 65535 && port != 3306
                && "/scm".equals(db) && Integer.toString(port).equals(verifiedPort)) {
            Path dir = Path.of(temporaryDir);
            windowsFixture = dir.getFileName() != null
                    && dir.getFileName().toString().matches("scm_mysql_dbtests_\\d{14}")
                    && Files.isDirectory(dir.resolve("data"));
        }
        if (!loopback || !safeOptions || !(ciFixture || dedicatedFixture || windowsFixture)) {
            throw new IllegalStateException("Real-DB regression refused: use the loopback scm_regression E2E database"
                    + " (or a verified temporary Windows/CI fixture)");
        }
    }

    /** Spring Boot's init runner can write before @BeforeEach; reject unsafe URLs before context refresh. */
    static final class SpringInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            requireEnvironment();
        }
    }
}
