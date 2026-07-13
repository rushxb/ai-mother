package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable task limits copied from configuration when a task starts.
 *
 * <p>Copying the values makes an in-flight task deterministic even if runtime configuration is
 * refreshed later.</p>
 */
public record GenerationExecutionLimits(
        Duration taskTimeout,
        Duration modelCallTimeout,
        Duration minimumOperationTimeout,
        Map<GenerationBudgetKind, Integer> budgets
) {

    public GenerationExecutionLimits {
        requirePositive(taskTimeout, "taskTimeout");
        requirePositive(modelCallTimeout, "modelCallTimeout");
        requirePositive(minimumOperationTimeout, "minimumOperationTimeout");
        if (modelCallTimeout.compareTo(taskTimeout) > 0) {
            throw new IllegalArgumentException("modelCallTimeout 不能大于 taskTimeout");
        }
        EnumMap<GenerationBudgetKind, Integer> normalizedBudgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            Integer limit = budgets == null ? null : budgets.get(kind);
            if (limit == null || limit <= 0) {
                throw new IllegalArgumentException(kind + " 的预算必须大于 0");
            }
            normalizedBudgets.put(kind, limit);
        }
        budgets = Map.copyOf(normalizedBudgets);
    }

    public int limit(GenerationBudgetKind kind) {
        Integer value = budgets.get(kind);
        if (value == null) {
            throw new IllegalArgumentException("未配置预算类型：" + kind);
        }
        return value;
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
