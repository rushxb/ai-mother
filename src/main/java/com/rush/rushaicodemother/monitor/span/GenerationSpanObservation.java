package com.rush.rushaicodemother.monitor.span;

import java.time.Instant;
import java.util.Objects;

/** 生成运行时发出的不可变的完整跨度。 */
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

    /** 创建生成跨度观测实例并完成必要的依赖和初始状态设置。 */
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

    /** 校验并返回有效的{@code Text}。 */
    private static void requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds maximum length");
        }
    }
}
