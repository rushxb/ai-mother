package com.rush.rushaicodemother.monitor.span;

import java.time.Instant;
import java.util.Objects;

/** Immutable completed span emitted by the generation runtime. */
public record GenerationSpanObservation(
        String spanId,
        String taskId,
        String stage,
        GenerationSpanCategory category,
        String status,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        String detail
) {

    public static final int MAX_DETAIL_LENGTH = 1_000;

    public GenerationSpanObservation {
        requireText(spanId, "spanId", 36);
        requireText(taskId, "taskId", 128);
        requireText(stage, "stage", 96);
        Objects.requireNonNull(category, "category");
        requireText(status, "status", 32);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot be before startedAt");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative");
        }
        detail = detail == null ? "" : detail;
        if (detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail exceeds maximum length");
        }
    }

    private static void requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length");
        }
    }
}
