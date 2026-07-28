package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** 不可变的特定于路由的执行合约通过持久的任务命令进行持久化。 */
public record GenerationSlaEnvelope(
        String profile,
        Duration firstPreviewTimeout,
        Duration firstPreviewCompletionReserve,
        Duration totalTimeout,
        Duration modelCallTimeout,
        Duration minimumOperationTimeout,
        Map<GenerationBudgetKind, Integer> budgets,
        String reason
) {

    /** 兼容升级前没有首预览完成预留字段的任务命令。 */
    public GenerationSlaEnvelope(
            String profile,
            Duration firstPreviewTimeout,
            Duration totalTimeout,
            Duration modelCallTimeout,
            Duration minimumOperationTimeout,
            Map<GenerationBudgetKind, Integer> budgets,
            String reason
    ) {
        this(profile, firstPreviewTimeout, null, totalTimeout,
                modelCallTimeout, minimumOperationTimeout, budgets, reason);
    }

    /** 创建生成{@code Sla}{@code Envelope}实例并完成必要的依赖和初始状态设置。 */
    public GenerationSlaEnvelope {
        profile = normalize(profile, "default");
        reason = normalize(reason, "route_profile");
        requirePositive(firstPreviewTimeout, "firstPreviewTimeout");
        requirePositive(totalTimeout, "totalTimeout");
        requirePositive(modelCallTimeout, "modelCallTimeout");
        requirePositive(minimumOperationTimeout, "minimumOperationTimeout");
        if (firstPreviewTimeout.compareTo(totalTimeout) > 0) {
            throw new IllegalArgumentException("首预览超时不能大于任务总超时");
        }
        if (minimumOperationTimeout.compareTo(firstPreviewTimeout) >= 0) {
            throw new IllegalArgumentException("首预览完成预留必须为可选操作保留最小执行窗口");
        }
        Duration availablePreviewWindow = firstPreviewTimeout.minus(minimumOperationTimeout);
        firstPreviewCompletionReserve = firstPreviewCompletionReserve == null
                ? minimumDuration(minimumOperationTimeout, availablePreviewWindow)
                : firstPreviewCompletionReserve;
        requirePositive(firstPreviewCompletionReserve, "firstPreviewCompletionReserve");
        if (firstPreviewCompletionReserve.compareTo(availablePreviewWindow) > 0) {
            throw new IllegalArgumentException("首预览完成预留必须为可选操作保留最小执行窗口");
        }
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                totalTimeout, modelCallTimeout, minimumOperationTimeout,
                firstPreviewCompletionReserve, budgets);
        EnumMap<GenerationBudgetKind, Integer> normalized = new EnumMap<>(GenerationBudgetKind.class);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            normalized.put(kind, limits.limit(kind));
        }
        budgets = Map.copyOf(normalized);
    }

    /**
 * 将当前对象转换为限制。
 *
 * @return 限制
 */
    public GenerationExecutionLimits toLimits() {
        return new GenerationExecutionLimits(
                totalTimeout, modelCallTimeout, minimumOperationTimeout,
                firstPreviewCompletionReserve, budgets);
    }

    public Instant firstPreviewDeadline(Instant submittedAt) {
        return submittedAt.plus(firstPreviewTimeout);
    }

    public Instant totalDeadline(Instant submittedAt) {
        return submittedAt.plus(totalTimeout);
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
    }

    private static Duration minimumDuration(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
