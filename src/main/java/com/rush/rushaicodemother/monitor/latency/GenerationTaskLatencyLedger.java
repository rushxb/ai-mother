package com.rush.rushaicodemother.monitor.latency;

import java.time.Instant;
import java.util.List;

/** 管理员诊断使用的不可变任务级挂钟归因。 */
public record GenerationTaskLatencyLedger(
        String taskId,
        Long appId,
        Long userId,
        String route,
        String status,
        String stage,
        Instant submittedAt,
        Instant deadlineAt,
        Instant completedAt,
        Instant calculatedAt,
        long totalLatencyMs,
        long attributedLatencyMs,
        long unattributedLatencyMs,
        double attributionCoveragePercent,
        long overlappingLatencyMs,
        long deadlineOvershootMs,
        int spanCount,
        int usableSpanCount,
        boolean spanLimitReached,
        String dominantCategory,
        List<CategoryLatency> categories
) {

    public GenerationTaskLatencyLedger {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** 包含的持续时间可能与其他类别重叠；归因持续时间永远不会。 */
    public record CategoryLatency(
            String category,
            int spanCount,
            long attributedDurationMs,
            long inclusiveDurationMs,
            double taskPercent
    ) {
    }
}
