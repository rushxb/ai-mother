package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 提交前场景澄清已经消耗的有限模型预算。 */
public record GenerationPreflightUsage(
        int rootModelAttempts,
        int modelTurns,
        int providerFailoverAttempts
) {

    private static final GenerationPreflightUsage NONE = new GenerationPreflightUsage(0, 0, 0);

    public GenerationPreflightUsage {
        requireRange(rootModelAttempts, 0, 1, "preflight 根模型尝试次数");
        requireRange(modelTurns, 0, 1, "preflight 模型回合数");
        requireRange(providerFailoverAttempts, 0, 2, "preflight provider 尝试次数");
        if (modelTurns > 0 && rootModelAttempts == 0) {
            throw new IllegalArgumentException("preflight 模型回合必须属于根模型尝试");
        }
        if (providerFailoverAttempts > 0 && modelTurns == 0) {
            throw new IllegalArgumentException("preflight provider 尝试必须属于模型回合");
        }
    }

    public static GenerationPreflightUsage none() {
        return NONE;
    }

    public static GenerationPreflightUsage from(GenerationExecutionContext context) {
        Objects.requireNonNull(context, "preflight 执行上下文不能为空");
        return new GenerationPreflightUsage(
                context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT),
                context.used(GenerationBudgetKind.MODEL_TURN),
                context.used(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
    }

    public boolean consumedModelBudget() {
        return rootModelAttempts > 0 || modelTurns > 0 || providerFailoverAttempts > 0;
    }

    public Map<GenerationBudgetKind, Integer> asBudgetUsages() {
        EnumMap<GenerationBudgetKind, Integer> usages = new EnumMap<>(GenerationBudgetKind.class);
        usages.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, rootModelAttempts);
        usages.put(GenerationBudgetKind.MODEL_TURN, modelTurns);
        usages.put(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, providerFailoverAttempts);
        return Map.copyOf(usages);
    }

    /**
     * 将预检消耗纳入任务总预算，同时保留 worker 原有的路由预算。
     *
     * <p>worker 恢复上下文时会把这部分标记为已使用，因此扩展后的上限不会让
     * 运行期获得额外额度，只是避免预检挤占已冻结的路由执行预算。</p>
     */
    public GenerationSlaEnvelope includeIn(GenerationSlaEnvelope routeSla) {
        Objects.requireNonNull(routeSla, "路由 SLA 不能为空");
        if (!consumedModelBudget()) {
            return routeSla;
        }
        EnumMap<GenerationBudgetKind, Integer> budgets =
                new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, routeSla.toLimits().limit(kind));
        }
        asBudgetUsages().forEach((kind, used) -> budgets.compute(kind, (ignored, limit) -> limit + used));
        return new GenerationSlaEnvelope(
                routeSla.profile(),
                routeSla.firstPreviewTimeout(),
                routeSla.firstPreviewCompletionReserve(),
                routeSla.totalTimeout(),
                routeSla.modelCallTimeout(),
                routeSla.minimumOperationTimeout(),
                Map.copyOf(budgets),
                routeSla.reason());
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }
}
