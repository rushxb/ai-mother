package com.rush.rushaicodemother.orchestration.router;

import java.time.Instant;

/** 可用于路由策略的有界生产遥测。 */
public record GenerationRoutingTelemetrySnapshot(
        int recentTaskCount,
        int failedTaskCount,
        long averageDurationMs,
        int feedbackCount,
        int lowRatingCount,
        double averageRating,
        int queuedTaskCount,
        int runningTaskCount,
        int waitingApprovalTaskCount,
        int maxConcurrency,
        int queueCapacity,
        Instant capturedAt,
        boolean available
) {

    public static GenerationRoutingTelemetrySnapshot unavailable() {
        return new GenerationRoutingTelemetrySnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, Instant.EPOCH, false
        );
    }

    public double failureRate() {
        return recentTaskCount == 0 ? 0.0 : (double) failedTaskCount / recentTaskCount;
    }

    public double lowRatingRate() {
        return feedbackCount == 0 ? 0.0 : (double) lowRatingCount / feedbackCount;
    }

    public double runningPressure() {
        return (double) runningTaskCount / Math.max(1, maxConcurrency);
    }

    public double queuePressure() {
        return (double) queuedTaskCount / Math.max(1, queueCapacity);
    }
}
