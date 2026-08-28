package com.rush.rushaicodemother.service.prompt.canary;

import java.time.Instant;
import java.util.regex.Pattern;

/** 一次 Prompt 灰度评估的不可变发布身份和观测窗口。 */
public record PromptCanaryEvaluationRequest(
        String promptKey,
        long releaseRevision,
        long bundleRevision,
        String bundleId,
        String stableVersion,
        String stableContentHash,
        String canaryVersion,
        String canaryContentHash,
        Instant windowStart,
        Instant windowEnd
) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public PromptCanaryEvaluationRequest {
        promptKey = require(promptKey, KEY_PATTERN, "Prompt key");
        stableVersion = require(stableVersion, VERSION_PATTERN, "稳定版本");
        canaryVersion = require(canaryVersion, VERSION_PATTERN, "灰度版本");
        bundleId = require(bundleId, SHA256_PATTERN, "Prompt 包指纹");
        stableContentHash = require(stableContentHash, SHA256_PATTERN, "稳定版本指纹");
        canaryContentHash = require(canaryContentHash, SHA256_PATTERN, "灰度版本指纹");
        if (releaseRevision <= 0 || bundleRevision <= 0 || releaseRevision > bundleRevision) {
            throw new IllegalArgumentException("Prompt 灰度发布修订无效");
        }
        if (stableVersion.equals(canaryVersion)
                || stableContentHash.equals(canaryContentHash)) {
            throw new IllegalArgumentException("Prompt 稳定与灰度身份必须不同");
        }
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("Prompt 灰度观测窗口无效");
        }
    }

    private static String require(String value, Pattern pattern, String label) {
        String normalized = value == null ? "" : value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(label + "无效");
        }
        return normalized;
    }
}
