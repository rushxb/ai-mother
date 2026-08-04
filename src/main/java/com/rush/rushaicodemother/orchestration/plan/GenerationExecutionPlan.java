package com.rush.rushaicodemother.orchestration.plan;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 任务提交前一次性生成的不可变执行约束快照。
 *
 * <p>计划只保存执行所需的有限结构化信息，不包含原始提示词、应用编号、用户编号等业务标识。</p>
 */
public record GenerationExecutionPlan(
        GenerationModeDecision route,
        GenerationPerformanceProfile modelProfile,
        ContextBudget contextBudget,
        ToolPolicy toolPolicy,
        ValidationGraph validationGraph,
        RepairBudget repairBudget,
        CommitPolicy commitPolicy,
        PreviewPolicy previewPolicy,
        GenerationSlaEnvelope sla
) {

    public GenerationExecutionPlan {
        Objects.requireNonNull(route, "执行计划路由不能为空");
        Objects.requireNonNull(modelProfile, "执行计划模型档位不能为空");
        Objects.requireNonNull(contextBudget, "执行计划上下文预算不能为空");
        Objects.requireNonNull(toolPolicy, "执行计划工具策略不能为空");
        Objects.requireNonNull(validationGraph, "执行计划验证图不能为空");
        Objects.requireNonNull(repairBudget, "执行计划修复预算不能为空");
        Objects.requireNonNull(commitPolicy, "执行计划提交策略不能为空");
        Objects.requireNonNull(previewPolicy, "执行计划预览策略不能为空");
        Objects.requireNonNull(sla, "执行计划 SLA 不能为空");
        if (validationGraph.level() != route.expectedValidationLevel()) {
            throw new IllegalArgumentException("执行计划验证等级必须与路由决策一致");
        }
        if (repairBudget.maxRounds() != sla.toLimits().limit(GenerationBudgetKind.REPAIR_ROUND)) {
            throw new IllegalArgumentException("执行计划修复预算必须与 SLA 一致");
        }
        if (toolPolicy.maxWriteOperations() != sla.toLimits().limit(GenerationBudgetKind.TOOL_WRITE)) {
            throw new IllegalArgumentException("执行计划写工具预算必须与 SLA 一致");
        }
        if (!previewPolicy.firstPreviewTimeout().equals(sla.firstPreviewTimeout())
                || !previewPolicy.completionReserve().equals(sla.firstPreviewCompletionReserve())) {
            throw new IllegalArgumentException("执行计划预览策略必须与 SLA 一致");
        }
    }

    /** 在保留已冻结 SLA 与其余执行约束的前提下，生成回退路由的新计划。 */
    public GenerationExecutionPlan withRoute(GenerationModeDecision routeDecision) {
        Objects.requireNonNull(routeDecision, "回退路由决策不能为空");
        return new GenerationExecutionPlan(
                routeDecision,
                modelProfile,
                contextBudget,
                toolPolicy,
                ValidationGraph.forLevel(routeDecision.expectedValidationLevel()),
                repairBudget,
                commitPolicy,
                previewPolicy,
                sla
        );
    }

    /** 上下文装配使用的令牌预算快照。 */
    public record ContextBudget(
            int generationMaxTokens,
            int repairMaxTokens,
            int maxSectionTokens,
            int minimumSectionTokens,
            int maxSemanticMemorySections,
            String tokenizerModel,
            double tokenSafetyMargin
    ) {
        public ContextBudget {
            requirePositive(generationMaxTokens, "生成上下文令牌预算");
            requirePositive(repairMaxTokens, "修复上下文令牌预算");
            requirePositive(maxSectionTokens, "上下文分段令牌上限");
            requirePositive(minimumSectionTokens, "上下文最小分段令牌数");
            requirePositive(maxSemanticMemorySections, "语义记忆分段上限");
            if (minimumSectionTokens > generationMaxTokens
                    || minimumSectionTokens > repairMaxTokens
                    || minimumSectionTokens > maxSectionTokens) {
                throw new IllegalArgumentException("上下文最小分段令牌数不能大于对应预算");
            }
            tokenizerModel = requireText(tokenizerModel, "分词器模型不能为空");
            if (!Double.isFinite(tokenSafetyMargin)
                    || tokenSafetyMargin < 1.0
                    || tokenSafetyMargin > 2.0) {
                throw new IllegalArgumentException("分词安全系数必须在 1.0 到 2.0 之间");
            }
        }
    }

    /** 工具调用次数与副作用安全边界。 */
    public record ToolPolicy(
            int maxInvocations,
            int maxWriteOperations,
            boolean writeOperationsRequireFence,
            boolean destructiveOperationsRequireApproval
    ) {
        public ToolPolicy {
            requirePositive(maxInvocations, "工具调用上限");
            requireNonNegative(maxWriteOperations, "写工具调用上限");
        }
    }

    /** 当前验证等级对应的有序验证步骤。 */
    public record ValidationGraph(ExpectedValidationLevel level, List<ValidationStep> steps) {
        public ValidationGraph {
            Objects.requireNonNull(level, "验证等级不能为空");
            if (steps == null || steps.isEmpty() || steps.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("验证步骤不能为空");
            }
            steps = List.copyOf(steps);
            if (!steps.equals(stepsFor(level))) {
                throw new IllegalArgumentException("验证步骤必须与验证等级一致");
            }
        }

        public static ValidationGraph forLevel(ExpectedValidationLevel level) {
            Objects.requireNonNull(level, "验证等级不能为空");
            return new ValidationGraph(level, stepsFor(level));
        }

        private static List<ValidationStep> stepsFor(ExpectedValidationLevel level) {
            return switch (level) {
                case FAST -> List.of(ValidationStep.FAST_CHECK);
                case BUILD -> List.of(ValidationStep.FAST_CHECK, ValidationStep.BUILD);
                case EXPERT -> List.of(
                        ValidationStep.FAST_CHECK,
                        ValidationStep.BUILD,
                        ValidationStep.EXPERT_CHECK);
            };
        }
    }

    public enum ValidationStep {
        FAST_CHECK,
        BUILD,
        EXPERT_CHECK
    }

    /** 自动修复的轮数上限及现有质量档位升级约束。 */
    public record RepairBudget(int maxRounds, boolean upgradeModelProfileOnRepair) {
        public RepairBudget {
            requireNonNegative(maxRounds, "自动修复轮数");
        }
    }

    /** 工作区提交和失败回滚约束。 */
    public record CommitPolicy(boolean requireValidationSuccess, boolean rollbackOnFailure) {
    }

    /** 首次可预览里程碑的时间约束。 */
    public record PreviewPolicy(Duration firstPreviewTimeout, Duration completionReserve) {
        public PreviewPolicy {
            requirePositive(firstPreviewTimeout, "首预览超时");
            requirePositive(completionReserve, "首预览完成预留");
            if (completionReserve.compareTo(firstPreviewTimeout) >= 0) {
                throw new IllegalArgumentException("首预览完成预留必须小于首预览超时");
            }
        }
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + "必须大于 0");
        }
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + "不能小于 0");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + "必须大于 0");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}