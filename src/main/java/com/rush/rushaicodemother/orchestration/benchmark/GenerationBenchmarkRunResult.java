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
        String failureReason
) {
    public GenerationBenchmarkRunResult {
        taskId = taskId == null ? "" : taskId;
        mode = mode == null ? "" : mode;
        durationMs = Math.max(0, durationMs);
        aiCallCount = Math.max(0, aiCallCount);
        toolCallCount = Math.max(0, toolCallCount);
        repairRounds = Math.max(0, repairRounds);
        failureReason = failureReason == null ? "" : failureReason;
    }
}
