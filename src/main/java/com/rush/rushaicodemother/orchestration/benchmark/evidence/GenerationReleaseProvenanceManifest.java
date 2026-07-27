package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.util.Locale;

/** CI、Benchmark Worker 与发布控制面共用的不可变发布来源清单。 */
public record GenerationReleaseProvenanceManifest(
        String runtimeConfigFingerprint,
        String gitCommit
) {

    public GenerationReleaseProvenanceManifest {
        runtimeConfigFingerprint = normalizeSha256(runtimeConfigFingerprint);
        gitCommit = normalizeGitCommit(gitCommit);
    }

    public static boolean isSha256(String value) {
        return normalize(value).matches("[0-9a-f]{64}");
    }

    public static boolean isFullGitCommit(String value) {
        return normalize(value).matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    }

    private static String normalizeSha256(String value) {
        String normalized = normalize(value);
        if (!isSha256(normalized)) {
            throw new IllegalArgumentException("运行配置指纹必须是 SHA-256");
        }
        return normalized;
    }

    private static String normalizeGitCommit(String value) {
        String normalized = normalize(value);
        if (!isFullGitCommit(normalized)) {
            throw new IllegalArgumentException("Git 提交必须是完整提交哈希");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
