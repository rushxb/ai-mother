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
