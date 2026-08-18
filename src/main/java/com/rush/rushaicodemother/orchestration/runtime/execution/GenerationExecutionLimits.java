package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

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
        GenerationCompletionRequirements completionRequirements,
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
                null, null, budgets);
    }

    /** 兼容已经显式配置首预览完成预留的调用方。 */
    public GenerationExecutionLimits(
            Duration taskTimeout,
            Duration modelCallTimeout,
            Duration minimumOperationTimeout,
            Duration firstPreviewCompletionReserve,
            Map<GenerationBudgetKind, Integer> budgets
    ) {
        this(taskTimeout, modelCallTimeout, minimumOperationTimeout,
                firstPreviewCompletionReserve, null, budgets);
    }

    /** 为测试、恢复与任务规划显式绑定下游完成需求。 */
    public GenerationExecutionLimits(
            Duration taskTimeout,
            Duration modelCallTimeout,
            Duration minimumOperationTimeout,
            GenerationCompletionRequirements completionRequirements,
            Map<GenerationBudgetKind, Integer> budgets
    ) {
        this(taskTimeout, modelCallTimeout, minimumOperationTimeout,
                null, completionRequirements, budgets);
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
        // 旧检查点没有该字段时采用最保守的完成图，宁可少开一轮模型也不能挤占验证时间。
        completionRequirements = completionRequirements == null
                ? GenerationCompletionRequirements.buildAndRuntime()
                : completionRequirements;
        Map<GenerationBudgetKind, Integer> sourceBudgets = budgets == null ? Map.of() : budgets;
        Integer rootAttempts = sourceBudgets.get(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        EnumMap<GenerationBudgetKind, Integer> normalizedBudgets = new EnumMap<>(GenerationBudgetKind.class);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            Integer limit = sourceBudgets.get(kind);
            if (limit == null && rootAttempts != null) {
                limit = legacyDerivedLimit(kind, rootAttempts);
            }
            if (limit == null || limit < minimumAllowed(kind)) {
                throw new IllegalArgumentException(kind + " 的预算低于允许下限");
            }
            normalizedBudgets.put(kind, limit);
        }
        budgets = Map.copyOf(normalizedBudgets);
    }

    /** 返回仅替换冻结完成需求的新限制实例。 */
    public GenerationExecutionLimits withCompletionRequirements(
            GenerationCompletionRequirements requirements
    ) {
        return new GenerationExecutionLimits(
                taskTimeout,
                modelCallTimeout,
                minimumOperationTimeout,
                firstPreviewCompletionReserve,
                Objects.requireNonNull(requirements, "完成需求不能为空"),
                budgets
        );
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

    /** 模型执行预算必须为正；副作用预算允许为零，以表达结构化禁用。 */
    private static int minimumAllowed(GenerationBudgetKind kind) {
        return switch (kind) {
            case ROOT_MODEL_ATTEMPT, MODEL_TURN, PROVIDER_FAILOVER_ATTEMPT -> 1;
            case TOOL_WRITE, BUILD_EXECUTION, REPAIR_ROUND -> 0;
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
