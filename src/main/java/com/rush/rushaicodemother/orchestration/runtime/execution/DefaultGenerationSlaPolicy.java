package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Selects normal or saturation-contained budgets without coupling workers to telemetry storage. */
@Component
@RequiredArgsConstructor
public class DefaultGenerationSlaPolicy implements GenerationSlaPolicy {

    private final GenerationSlaProperties properties;

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
                profile.getTotalTimeout(),
                profile.getModelCallTimeout(),
                profile.getMinimumOperationTimeout(),
                budgets(profile),
                reason
        );
    }

    private Map<GenerationBudgetKind, Integer> budgets(GenerationSlaProperties.Profile profile) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        budgets.put(GenerationBudgetKind.MODEL_ATTEMPT, profile.getMaxModelAttempts());
        budgets.put(GenerationBudgetKind.TOOL_WRITE, profile.getMaxToolWrites());
        budgets.put(GenerationBudgetKind.BUILD_EXECUTION, profile.getMaxBuildExecutions());
        budgets.put(GenerationBudgetKind.REPAIR_ROUND, profile.getMaxRepairRounds());
        return Map.copyOf(budgets);
    }
}
