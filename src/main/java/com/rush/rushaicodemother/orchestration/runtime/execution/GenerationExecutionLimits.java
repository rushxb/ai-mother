package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 任务启动时从配置复制的不可变任务限制。
 *
 * <p>即使运行时配置是，复制值也会使运行中的任务具有确定性
 * 稍后刷新。</p>
 */
public record GenerationExecutionLimits(
        Duration taskTimeout,
        Duration modelCallTimeout,
        Duration minimumOperationTimeout,
        Duration firstPreviewCompletionReserve,
        Map<GenerationBudgetKind, Integer> budgets
) {

    private static final int LEGACY_MODEL_TURNS_PER_ROOT_ATTEMPT = 8;
    private static final int LEGACY_FAILOVERS_PER_ROOT_ATTEMPT = 2;

    /** 兼容未携带首预览完成预留的旧调用方。 */
    public GenerationExecutionLimits(
            Duration taskTimeout,
            Duration modelCallTimeout,
            Duration minimumOperationTimeout,
            Map<GenerationBudgetKind, Integer> budgets
    ) {
        this(taskTimeout, modelCallTimeout, minimumOperationTimeout,
                null, budgets);
    }

    /** 创建生成执行限制实例并完成必要的依赖和初始状态设置。 */
    public GenerationExecutionLimits {
        requirePositive(taskTimeout, "taskTimeout");
        requirePositive(modelCallTimeout, "modelCallTimeout");
        requirePositive(minimumOperationTimeout, "minimumOperationTimeout");
        if (modelCallTimeout.compareTo(taskTimeout) > 0) {
            throw new IllegalArgumentException("modelCallTimeout 不能大于 taskTimeout");
        }
        if (minimumOperationTimeout.compareTo(taskTimeout) >= 0) {
            throw new IllegalArgumentException("首预览完成预留必须为可选操作保留最小执行窗口");
        }
        Duration availablePreviewWindow = taskTimeout.minus(minimumOperationTimeout);
        firstPreviewCompletionReserve = firstPreviewCompletionReserve == null
                ? minimumDuration(minimumOperationTimeout, availablePreviewWindow)
                : firstPreviewCompletionReserve;
        requirePositive(firstPreviewCompletionReserve, "firstPreviewCompletionReserve");
        if (firstPreviewCompletionReserve.compareTo(availablePreviewWindow) > 0) {
            throw new IllegalArgumentException("首预览完成预留必须为可选操作保留最小执行窗口");
        }
        Map<GenerationBudgetKind, Integer> sourceBudgets = budgets == null ? Map.of() : budgets;
        Integer rootAttempts = sourceBudgets.get(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        EnumMap<GenerationBudgetKind, Integer> normalizedBudgets = new EnumMap<>(GenerationBudgetKind.class);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            Integer limit = sourceBudgets.get(kind);
            if (limit == null && rootAttempts != null) {
                limit = legacyDerivedLimit(kind, rootAttempts);
            }
            if (limit == null || limit <= 0) {
                throw new IllegalArgumentException(kind + " 的预算必须大于 0");
            }
            normalizedBudgets.put(kind, limit);
        }
        budgets = Map.copyOf(normalizedBudgets);
    }

    /**
 * 返回限制。
 *
 * @param kind 类别
 * @return 计算或处理后的数值结果
 */
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

    private static Duration minimumDuration(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Integer legacyDerivedLimit(GenerationBudgetKind kind, int rootAttempts) {
        return switch (kind) {
            case MODEL_TURN -> safeMultiply(rootAttempts, LEGACY_MODEL_TURNS_PER_ROOT_ATTEMPT);
            case PROVIDER_FAILOVER_ATTEMPT -> safeMultiply(rootAttempts, LEGACY_FAILOVERS_PER_ROOT_ATTEMPT);
            default -> null;
        };
    }

    /** 返回安全{@code Multiply}。 */
    private static int safeMultiply(int value, int multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("兼容生成预算超出整数范围", overflow);
        }
    }
}
