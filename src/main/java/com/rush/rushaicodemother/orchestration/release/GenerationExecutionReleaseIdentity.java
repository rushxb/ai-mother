package com.rush.rushaicodemother.orchestration.release;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseProvenanceManifest;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;

import java.util.Locale;

/**
 * 一次生成决策所使用的、可归因的发布身份。
 *
 * <p>该清单保留组成发布指纹的真实事实，避免只持有一个无法解释来源的散列值。</p>
 */
public record GenerationExecutionReleaseIdentity(
        String gitCommit,
        boolean dirtyBuild,
        String runtimePolicyFingerprint,
        String promptBundleFingerprint,
        String modelFleetFingerprint,
        String decisionRuleVersion
) {

    private static final String DECISION_POLICY_SCHEMA = "generation-decision-policy|";
    private static final String RELEASE_IDENTITY_SCHEMA = "generation-execution-release|";

    public GenerationExecutionReleaseIdentity {
        gitCommit = normalizeGitCommit(gitCommit);
        runtimePolicyFingerprint = normalizeSha256(
                runtimePolicyFingerprint, "运行配置与策略指纹");
        promptBundleFingerprint = normalizeSha256(
                promptBundleFingerprint, "Prompt 包指纹");
        modelFleetFingerprint = normalizeSha256(
                modelFleetFingerprint, "模型池指纹");
        decisionRuleVersion = requireText(decisionRuleVersion, "决策规则版本");
    }

    /** 返回由真实构建、运行策略与规则版本共同决定的策略指纹。 */
    public String decisionPolicyFingerprint() {
        return fingerprint(
                DECISION_POLICY_SCHEMA,
                gitCommit,
                Boolean.toString(dirtyBuild),
                runtimePolicyFingerprint,
                decisionRuleVersion);
    }

    /** 返回覆盖代码、配置、策略、Prompt 与模型的完整发布指纹。 */
    public String releaseFingerprint() {
        return fingerprint(
                RELEASE_IDENTITY_SCHEMA,
                gitCommit,
                Boolean.toString(dirtyBuild),
                runtimePolicyFingerprint,
                promptBundleFingerprint,
                modelFleetFingerprint,
                decisionPolicyFingerprint());
    }

    private static String normalizeGitCommit(String value) {
        String normalized = normalize(value);
        if (!GenerationReleaseProvenanceManifest.isFullGitCommit(normalized)) {
            throw new IllegalArgumentException("Git 提交必须是完整提交哈希");
        }
        return normalized;
    }

    private static String normalizeSha256(String value, String label) {
        String normalized = normalize(value);
        if (!GenerationReleaseProvenanceManifest.isSha256(normalized)) {
            throw new IllegalArgumentException(label + "必须是 SHA-256");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private static String fingerprint(String schema, String... values) {
        StringBuilder canonical = new StringBuilder(schema);
        for (String value : values) {
            ReleaseCandidateFingerprint.appendField(canonical, value);
        }
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }
}
