package com.rush.rushaicodemother.infrastructure.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDiagnosticSanitizerTest {

    @Test
    void shouldRedactCommonSecretsAndAbsolutePathsWithoutDestroyingBuildDiagnostics() {
        String diagnostic = """
                D:\\Users\\rush\\workspace\\src\\App.vue:17:9 - error TS2307: Cannot find module './missing'
                provider-api-key=secret-value
                Authorization: Bearer abc123
                registry-token: registry-secret
                GITHUB_TOKEN=github-secret
                "client_secret": "json secret with spaces"
                npm_config_//registry.npmjs.org/:_authToken=npm-secret
                https://user:password@example.com/private?token=query-secret
                tokenCount=42
                """;

        String sanitized = PublicDiagnosticSanitizer.sanitizeForPublicOutput(diagnostic);

        assertFalse(sanitized.contains("secret-value"));
        assertFalse(sanitized.contains("abc123"));
        assertFalse(sanitized.contains("registry-secret"));
        assertFalse(sanitized.contains("github-secret"));
        assertFalse(sanitized.contains("json secret with spaces"));
        assertFalse(sanitized.contains("npm-secret"));
        assertFalse(sanitized.contains("password"));
        assertFalse(sanitized.contains("query-secret"));
        assertFalse(sanitized.contains("D:\\Users\\rush"));
        assertTrue(sanitized.contains("App.vue:17:9"));
        assertTrue(sanitized.contains("TS2307"));
        assertTrue(sanitized.contains("Cannot find module './missing'"));
        assertTrue(sanitized.contains("tokenCount=42"));
        assertTrue(sanitized.contains("[REDACTED]"));
    }

    @Test
    void shouldRedactAbsolutePathsUnderAnyRootDirectory() {
        // 曾按 /home|/var|/tmp|... 白名单匹配，白名单外的根目录会整段泄漏主机目录结构；
        // 以 root 运行时工作区位于 /root/... 正好落在白名单外。这里固定「任意根目录都脱敏」。
        for (String directory : new String[]{
                "/root/ai-mother/tmp/code_output", "/home/rush/workspace",
                "/data/generation/workspace", "/mnt/build/cache",
                "/Users/rush/projects", "/srv/app/output"
        }) {
            String sanitized = PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                    directory + "/App.vue:3:1 build completed");

            assertFalse(sanitized.contains(directory),
                    "绝对路径目录部分必须脱敏: " + directory);
            assertTrue(sanitized.contains("App.vue:3:1"),
                    "必须保留可定位的文件名与行列号: " + directory);
            assertTrue(sanitized.contains("build completed"));
        }
    }

    @Test
    void shouldKeepRelativePathsAndUrlPathsIntact() {
        // 脱敏不得误伤相对路径与 URL 路径片段，否则构建诊断会失去可读性。
        String sanitized = PublicDiagnosticSanitizer.sanitizeForPublicOutput(
                "src/components/App.vue:3:1 failed; see https://example.com/docs/guide for help");

        assertTrue(sanitized.contains("src/components/App.vue:3:1"));
        assertTrue(sanitized.contains("https://example.com/docs/guide"));
    }

    @Test
    void shouldRedactPrivateKeyBodiesAndBoundOversizedOutput() {
        String diagnostic = "before\n-----BEGIN PRIVATE KEY-----\nprivate-key-body\n-----END PRIVATE KEY-----\n"
                + "x".repeat(20_000)
                + "\nimportant-tail";

        String sanitized = PublicDiagnosticSanitizer.sanitizeForPublicOutput(diagnostic, 1_000);

        assertFalse(sanitized.contains("private-key-body"));
        assertTrue(sanitized.contains("[REDACTED PRIVATE KEY]"));
        assertTrue(sanitized.contains("diagnostic output truncated"));
        assertTrue(sanitized.contains("important-tail"));
        assertTrue(sanitized.length() <= 1_000);
    }
}
