package com.rush.rushaicodemother.service.prompt.canary;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** 可重放、内容寻址的 Prompt 灰度门禁证据。 */
public record PromptCanaryAssessment(
        String assessmentId,
        PromptCanaryEvaluationRequest request,
        PromptCanaryDecision decision,
        List<String> violations,
        long stableTaskCount,
        long canaryTaskCount,
        long ambiguousTaskCount,
        long invalidAttributionTaskCount,
        String evidenceJson,
        String evidenceHash,
        Instant evaluatedAt
) {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public PromptCanaryAssessment {
        assessmentId = assessmentId == null ? "" : assessmentId.trim().toLowerCase();
        if (!UUID_PATTERN.matcher(assessmentId).matches() || request == null || decision == null
                || stableTaskCount < 0 || canaryTaskCount < 0
                || ambiguousTaskCount < 0 || invalidAttributionTaskCount < 0
                || evidenceJson == null || evidenceJson.isBlank()
                || evidenceHash == null || !SHA256_PATTERN.matcher(evidenceHash).matches()
                || evaluatedAt == null) {
            throw new IllegalArgumentException("Prompt 灰度评估证据不完整");
        }
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
