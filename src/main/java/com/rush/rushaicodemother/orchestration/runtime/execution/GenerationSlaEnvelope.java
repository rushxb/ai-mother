package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Immutable route-specific execution contract persisted with a durable task command. */
public record GenerationSlaEnvelope(
        String profile,
        Duration firstPreviewTimeout,
        Duration totalTimeout,
        Duration modelCallTimeout,
        Duration minimumOperationTimeout,
        Map<GenerationBudgetKind, Integer> budgets,
        String reason
) {

    public GenerationSlaEnvelope {
        profile = normalize(profile, "default");
        reason = normalize(reason, "route_profile");
        requirePositive(firstPreviewTimeout, "firstPreviewTimeout");
        requirePositive(totalTimeout, "totalTimeout");
        requirePositive(modelCallTimeout, "modelCallTimeout");
        requirePositive(minimumOperationTimeout, "minimumOperationTimeout");
        if (firstPreviewTimeout.compareTo(totalTimeout) > 0) {
            throw new IllegalArgumentException("firstPreviewTimeout cannot exceed totalTimeout");
        }
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                totalTimeout, modelCallTimeout, minimumOperationTimeout, budgets);
        EnumMap<GenerationBudgetKind, Integer> normalized = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            normalized.put(kind, limits.limit(kind));
        }
        budgets = Map.copyOf(normalized);
    }

    public GenerationExecutionLimits toLimits() {
        return new GenerationExecutionLimits(
                totalTimeout, modelCallTimeout, minimumOperationTimeout, budgets);
    }

    public Instant firstPreviewDeadline(Instant submittedAt) {
        return submittedAt.plus(firstPreviewTimeout);
    }

    public Instant totalDeadline(Instant submittedAt) {
        return submittedAt.plus(totalTimeout);
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
