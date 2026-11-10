package com.group.scm.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseGuardTest {
    private static final Map<String, String> LOCAL = Map.of(
            "E2E_PROJECT", "oss-e2e-fix-1008", "SCM_DB_TEST_VERIFIED_PORT", "13391");

    @Test void acceptsOnlyVerifiedLoopbackRegressionAndGitHubActionsFixture() {
        assertDoesNotThrow(() -> TestDatabaseGuard.requireSafeUrl(
                "jdbc:mysql://127.0.0.1:13391/scm_regression?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false", LOCAL));
        assertDoesNotThrow(() -> TestDatabaseGuard.requireSafeUrl(
                "jdbc:mysql://127.0.0.1:3306/scm?useSSL=false", Map.of("GITHUB_ACTIONS", "true")));
    }

    @Test void acceptsOnlyLiveTemporaryWindowsPreflightDirectory(@TempDir Path root) throws Exception {
        Path temporaryServer = root.resolve("scm_mysql_dbtests_20261008000000");
        Files.createDirectories(temporaryServer.resolve("data"));
        assertDoesNotThrow(() -> TestDatabaseGuard.requireSafeUrl(
                "jdbc:mysql://127.0.0.1:15432/scm", Map.of(
                        "SCM_DB_TEST_VERIFIED_PORT", "15432", "SCM_DB_TEST_TEMP_DIR", temporaryServer.toString())));
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.requireSafeUrl(
                "jdbc:mysql://127.0.0.1:15432/scm", Map.of(
                        "SCM_DB_TEST_VERIFIED_PORT", "15432", "SCM_DB_TEST_TEMP_DIR", root.toString())));
    }

    @Test void rejectsSharedRemoteOrUnverifiedTargetsBeforeOpeningAConnection() {
        for (String url : new String[] {
                "jdbc:mysql://db.example.test:13391/scm_regression",
                "jdbc:mysql://127.0.0.1:13391/scm",
                "jdbc:mysql://127.0.0.1:3306/scm_regression",
                "jdbc:mysql://127.0.0.1:13392/scm_regression",
                "jdbc:mysql://127.0.0.1:13391/scm_regression/extra",
                "jdbc:mysql://user@127.0.0.1:13391/scm_regression",
                "jdbc:mysql://127.0.0.1:13391/scm_regression?socketFactory=unexpected",
                "jdbc:mysql://127.0.0.1:13391/scm_regression?useSSL=false&address=remote",
                "jdbc:postgresql://127.0.0.1:13391/scm_regression"
        }) assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.requireSafeUrl(url, LOCAL), url);
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.requireSafeUrl(
                "jdbc:mysql://127.0.0.1:13391/scm_regression", Map.of("E2E_PROJECT", "oss-e2e-fix-1008")));
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.requireSafeUrl(
                "jdbc:mysql://127.0.0.1:3306/scm", Map.of("GITHUB_ACTIONS", "false")));
    }
}
