package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 从 Heavy 生成的结构化制品和执行上下文中提取完成证据。 */
public final class HeavyGenerationCompletionEvidenceFactory {

    private HeavyGenerationCompletionEvidenceFactory() {
    }

    public static GenerationCompletionEvidenceSet collect(
            GenerationPreparation preparation,
            GenerationSession session
    ) {
        if (preparation == null || session == null) {
            return GenerationCompletionEvidenceSet.empty();
        }
        List<GenerationCompletionEvidence> evidence = new ArrayList<>();
        if (hasArtifact(preparation, "requirements") || hasArtifact(preparation, "generation_spec")) {
            evidence.add(GenerationCompletionEvidence.of(
                    GenerationCompletionEvidenceType.INTENT_COVERAGE,
                    "heavy_preparation",
                    "需求或生成规范已形成结构化制品"));
        }
        int mutationCount = session.executionContext() == null
                ? 0
                : session.executionContext().successfulWorkspaceMutationCount();
        if (mutationCount > 0 || diffChanged(preparation, session)) {
            evidence.add(GenerationCompletionEvidence.of(
                    GenerationCompletionEvidenceType.WORKSPACE_CHANGE,
                    "heavy_workspace",
                    "工作区存在已确认的有效变更"));
        } else if (hasNoChangeJustification(preparation)) {
            evidence.add(GenerationCompletionEvidence.of(
                    GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION,
                    "heavy_workspace",
                    "已形成结构化无需修改证明"));
        }
        GenerationVerificationEvidenceRecorder.latestObservation(preparation)
                .ifPresent(observation -> addVerificationEvidence(observation, evidence));
        return new GenerationCompletionEvidenceSet(evidence);
    }

    private static void addVerificationEvidence(
            GenerationValidationObservation observation,
            List<GenerationCompletionEvidence> evidence
    ) {
        Set<GenerationExecutionPlan.ValidationStep> steps = observation.passedSteps();
        addIfPresent(steps, GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                GenerationCompletionEvidenceType.FAST_VALIDATION, evidence);
        addIfPresent(steps, GenerationExecutionPlan.ValidationStep.BUILD,
                GenerationCompletionEvidenceType.BUILD_VALIDATION, evidence);
        addIfPresent(steps, GenerationExecutionPlan.ValidationStep.EXPERT_CHECK,
                GenerationCompletionEvidenceType.EXPERT_VALIDATION, evidence);
    }

    private static void addIfPresent(
            Set<GenerationExecutionPlan.ValidationStep> steps,
            GenerationExecutionPlan.ValidationStep step,
            GenerationCompletionEvidenceType evidenceType,
            List<GenerationCompletionEvidence> evidence
    ) {
        if (steps.contains(step)) {
            evidence.add(GenerationCompletionEvidence.of(
                    evidenceType,
                    GenerationVerificationEvidenceRecorder.ARTIFACT_KEY,
                    "验证步骤 " + step.name() + " 已通过"));
        }
    }

    private static boolean diffChanged(GenerationPreparation preparation,
                                       GenerationSession session) {
        GenerationArtifact artifact = preparation.artifact(DiffSummary.KEY);
        if (artifact == null) {
            return false;
        }
        Long expectedAppId = session.executionContext() == null
                ? null
                : session.executionContext().appId();
        try {
            return DiffSummary.fromArtifact(
                    artifact,
                    expectedAppId,
                    preparation.taskId()
            ).hasChanges();
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 损坏或串任务的检查点不具备完成证据资格，等待后续节点重新生成。
            return false;
        }
    }

    private static boolean hasNoChangeJustification(GenerationPreparation preparation) {
        GenerationArtifact artifact = preparation.artifact("no_change_justification");
        if (artifact == null || artifact.payload() == null) {
            return false;
        }
        Object reason = artifact.payload().get("reason");
        return reason instanceof String text && !text.isBlank();
    }

    private static boolean hasArtifact(GenerationPreparation preparation, String key) {
        GenerationArtifact artifact = preparation.artifact(key);
        return artifact != null && artifact.payload() != null && !artifact.payload().isEmpty();
    }
}
