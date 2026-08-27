package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.ObservedValidationCompletionEvidenceFactory;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;

import java.util.Objects;

/** AGENT_EDIT validator 的结构化结果，携带实际执行计划产生的完成证据。 */
public record AgentEditVerificationOutcome(
        BackgroundValidationService.ValidationResult validationResult,
        EditValidationPlan performedPlan,
        GenerationValidationObservation observation,
        GenerationCompletionEvidenceSet completionEvidence
) {

    public AgentEditVerificationOutcome {
        validationResult = Objects.requireNonNull(validationResult, "验证结果不能为空");
        performedPlan = Objects.requireNonNull(performedPlan, "实际验证计划不能为空");
        completionEvidence = completionEvidence == null
                ? GenerationCompletionEvidenceSet.empty()
                : completionEvidence;
    }

    public static AgentEditVerificationOutcome observed(
            BackgroundValidationService.ValidationResult validationResult,
            EditValidationPlan performedPlan,
            GenerationValidationObservation observation,
            int mutationCount
    ) {
        GenerationCompletionEvidenceSet evidence = validationResult != null && validationResult.isSuccess()
                ? ObservedValidationCompletionEvidenceFactory.forCompletedMutation(
                        mutationCount, observation)
                : GenerationCompletionEvidenceSet.empty();
        return new AgentEditVerificationOutcome(
                validationResult, performedPlan, observation, evidence);
    }

    public boolean success() {
        return validationResult.isSuccess();
    }

}
