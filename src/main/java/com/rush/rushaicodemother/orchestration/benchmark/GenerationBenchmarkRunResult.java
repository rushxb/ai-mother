package com.rush.rushaicodemother.orchestration.benchmark;

public record GenerationBenchmarkRunResult(
        String taskId,
        String mode,
        boolean success,
        boolean buildPassed,
        long durationMs,
        int aiCallCount,
        int toolCallCount,
        boolean fallback,
        int repairRounds,
        String failureReason,
        long totalTokens,
        long creditCost,
        long firstTokenLatencyMs,
        GenerationBenchmarkQualityEvidence qualityEvidence
) {
    public GenerationBenchmarkRunResult(String taskId,
                                        String mode,
                                        boolean success,
                                        boolean buildPassed,
                                        long durationMs,
                                        int aiCallCount,
                                        int toolCallCount,
                                        boolean fallback,
                                        int repairRounds,
                                        String failureReason,
                                        long totalTokens,
                                        long creditCost,
                                        long firstTokenLatencyMs) {
        this(taskId, mode, success, buildPassed, durationMs, aiCallCount, toolCallCount,
                fallback, repairRounds, failureReason, totalTokens, creditCost,
                firstTokenLatencyMs, GenerationBenchmarkQualityEvidence.empty());
    }

    public GenerationBenchmarkRunResult(String taskId,
                                        String mode,
                                        boolean success,
                                        boolean buildPassed,
                                        long durationMs,
                                        int aiCallCount,
                                        int toolCallCount,
                                        boolean fallback,
                                        int repairRounds,
                                        String failureReason) {
        this(taskId, mode, success, buildPassed, durationMs, aiCallCount, toolCallCount,
                fallback, repairRounds, failureReason, 0L, 0L, 0L,
                GenerationBenchmarkQualityEvidence.empty());
    }

    public GenerationBenchmarkRunResult {
        taskId = taskId == null ? "" : taskId;
        mode = mode == null ? "" : mode;
        durationMs = Math.max(0, durationMs);
        aiCallCount = Math.max(0, aiCallCount);
        toolCallCount = Math.max(0, toolCallCount);
        repairRounds = Math.max(0, repairRounds);
        failureReason = failureReason == null ? "" : failureReason;
        totalTokens = Math.max(0, totalTokens);
        creditCost = Math.max(0, creditCost);
        firstTokenLatencyMs = Math.max(0, firstTokenLatencyMs);
        qualityEvidence = qualityEvidence == null
                ? GenerationBenchmarkQualityEvidence.empty()
                : qualityEvidence;
    }
}
