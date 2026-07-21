package com.rush.rushaicodemother.monitor.latency;

import java.time.Instant;
import java.util.List;

/** Immutable task-level wall-clock attribution used by administrator diagnostics. */
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

    /** Inclusive duration may overlap other categories; attributed duration never does. */
    public record CategoryLatency(
            String category,
            int spanCount,
            long attributedDurationMs,
            long inclusiveDurationMs,
            double taskPercent
    ) {
    }
}
