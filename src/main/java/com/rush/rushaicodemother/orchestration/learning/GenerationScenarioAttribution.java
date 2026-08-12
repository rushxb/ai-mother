package com.rush.rushaicodemother.orchestration.learning;

import java.time.LocalDateTime;

/** 单任务场景到结果、反馈、模型 provenance 和成本的归因读模型。 */
public record GenerationScenarioAttribution(
        String taskId,
        Long appId,
        String intentSignature,
        String profileVersion,
        String decisionVersion,
        String route,
        String releaseIdentity,
        String status,
        Integer rating,
        String feedbackOutcome,
        Integer changedFileCount,
        Integer firstBuildPassed,
        Integer repairRounds,
        Long firstPreviewMillis,
        Long durationMs,
        Long totalTokens,
        Long creditCost,
        Long modelCallCount,
        Long modelErrorCount,
        String providers,
        String models,
        String promptTemplateFingerprints,
        String toolSchemaFingerprints,
        String modelConfigFingerprints,
        LocalDateTime completedAt
) {
}
