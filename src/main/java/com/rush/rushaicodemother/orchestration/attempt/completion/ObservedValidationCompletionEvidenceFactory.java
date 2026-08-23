package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;

import java.util.ArrayList;
import java.util.List;

/** 将完整生成结果与验证器事实观测映射为完成判定证据，不读取路由期望。 */
public final class ObservedValidationCompletionEvidenceFactory {

    private ObservedValidationCompletionEvidenceFactory() {
    }

    public static GenerationCompletionEvidenceSet forCompletedCreate(
            SlotFillResult result,
            GenerationValidationObservation observation
    ) {
        if (result == null
                || !result.provesIntentCoverage()
                || observation == null
                || result.patchOperationCount() <= 0) {
            return GenerationCompletionEvidenceSet.empty();
        }
        int mutationCount = result.patchOperationCount();
        String source = observation.source();
        List<GenerationCompletionEvidence> evidence = new ArrayList<>();
        evidence.add(GenerationCompletionEvidence.of(
                GenerationCompletionEvidenceType.INTENT_COVERAGE,
                source,
                "pipeline 已按冻结意图执行"));
        evidence.add(GenerationCompletionEvidence.of(
                GenerationCompletionEvidenceType.WORKSPACE_CHANGE,
                source,
                "已确认 " + mutationCount + " 项工作区变更"));
        addObservedStep(
                observation,
                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                GenerationCompletionEvidenceType.FAST_VALIDATION,
                "快速校验已通过",
                evidence);
        addObservedStep(
                observation,
                GenerationExecutionPlan.ValidationStep.BUILD,
                GenerationCompletionEvidenceType.BUILD_VALIDATION,
                "构建校验已通过",
                evidence);
        addObservedStep(
                observation,
                GenerationExecutionPlan.ValidationStep.EXPERT_CHECK,
                GenerationCompletionEvidenceType.EXPERT_VALIDATION,
                "专家级校验已通过",
                evidence);
        return new GenerationCompletionEvidenceSet(evidence);
    }

    private static void addObservedStep(
            GenerationValidationObservation observation,
            GenerationExecutionPlan.ValidationStep step,
            GenerationCompletionEvidenceType evidenceType,
            String summary,
            List<GenerationCompletionEvidence> evidence
    ) {
        if (observation.passedSteps().contains(step)) {
            evidence.add(GenerationCompletionEvidence.of(
                    evidenceType, observation.source(), summary));
        }
    }
}
