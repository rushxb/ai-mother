package com.rush.rushaicodemother.orchestration.benchmark;

/**
 * 生成基准测试Run执行结果。
 */
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
        Long firstPreviewLatencyMs,
        GenerationBenchmarkQualityEvidence qualityEvidence,
        String expectedRoute,
        boolean routeAllowed
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
                firstTokenLatencyMs, null, GenerationBenchmarkQualityEvidence.empty(), "", true);
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
                                        String failureReason,
                                        long totalTokens,
                                        long creditCost,
                                        long firstTokenLatencyMs,
                                        Long firstPreviewLatencyMs,
                                        GenerationBenchmarkQualityEvidence qualityEvidence) {
        this(taskId, mode, success, buildPassed, durationMs, aiCallCount, toolCallCount, fallback,
                repairRounds, failureReason, totalTokens, creditCost, firstTokenLatencyMs,
                firstPreviewLatencyMs, qualityEvidence, "", true);
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
                null, GenerationBenchmarkQualityEvidence.empty(), "", true);
    }

    /** 创建生成基准测试{@code Run}结果实例并完成必要的依赖和初始状态设置。 */
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
        if (firstPreviewLatencyMs != null && firstPreviewLatencyMs < 0) {
            throw new IllegalArgumentException("首预览延迟不能小于 0");
        }
        qualityEvidence = qualityEvidence == null
                ? GenerationBenchmarkQualityEvidence.empty()
                : qualityEvidence;
        expectedRoute = expectedRoute == null ? "" : expectedRoute;
    }

    /**
     * 是否观测到首个用户可见预览。
     *
     * <p>口径为「任一等级的最早预览」：暂定预览也算已观测。这与首预览截止线的语义一致 ——
     * 它约束的是用户多久看到东西，而非产物多久通过验证。若只认已验证预览，
     * 该指标会退化成任务总时长，失去诊断意义。</p>
     */
    public boolean firstPreviewObserved() {
        return firstPreviewLatencyMs != null;
    }
}
