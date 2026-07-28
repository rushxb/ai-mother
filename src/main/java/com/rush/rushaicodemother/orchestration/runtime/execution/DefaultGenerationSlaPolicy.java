package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 选择正常或饱和预算，无需将工作人员耦合到遥测存储。 */
@Component
@RequiredArgsConstructor
public class DefaultGenerationSlaPolicy implements GenerationSlaPolicy {

    private final GenerationSlaProperties properties;

    /**
 * 根据当前上下文解析默认生成{@code Sla}策略。
 *
 * @param decision 决策
 * @param targetType 目标类型
 * @return 默认生成{@code Sla}策略
 */
    @Override
    public GenerationSlaEnvelope resolve(GenerationModeDecision decision, CodeGenTypeEnum targetType) {
        Objects.requireNonNull(decision, "decision");
        GenerationSlaProperties.Profile profile =
                decision.decisionCode() == GenerationRoutingDecisionCode.TELEMETRY_SATURATION_CONTAINMENT
                        ? properties.getSaturatedAgentEdit()
                        : properties.profile(decision.mode());
        String reason = decision.decisionCode().name().toLowerCase(java.util.Locale.ROOT);
        return new GenerationSlaEnvelope(
                profile.getName(),
                profile.getFirstPreviewTimeout(),
                profile.getFirstPreviewCompletionReserve(),
                profile.getTotalTimeout(),
                profile.getModelCallTimeout(),
                profile.getMinimumOperationTimeout(),
                budgets(profile),
                reason
        );
    }

    private Map<GenerationBudgetKind, Integer> budgets(GenerationSlaProperties.Profile profile) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, profile.getMaxRootModelAttempts());
        budgets.put(GenerationBudgetKind.MODEL_TURN, profile.getMaxModelTurns());
        budgets.put(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT,
                profile.getMaxProviderFailoverAttempts());
        budgets.put(GenerationBudgetKind.TOOL_WRITE, profile.getMaxToolWrites());
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, profile.getMaxBuildExecutions());
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, profile.getMaxRepairRounds());
        return Map.copyOf(budgets);
    }
}
