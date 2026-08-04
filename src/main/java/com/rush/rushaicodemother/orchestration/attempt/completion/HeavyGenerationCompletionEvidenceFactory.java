package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        if (mutationCount > 0 || diffChanged(preparation.artifact("diff_summary"))) {
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
        if (preparation.qualityGateResult() != null && preparation.qualityGateResult().passed()) {
            evidence.add(GenerationCompletionEvidence.of(
                    GenerationCompletionEvidenceType.FAST_VALIDATION,
                    "quality_gate",
                    "生成前质量门禁已通过"));
        }
        addVerificationEvidence(preparation.artifact(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY), evidence);
        return new GenerationCompletionEvidenceSet(evidence);
    }

    private static void addVerificationEvidence(
            GenerationArtifact artifact,
            List<GenerationCompletionEvidence> evidence
    ) {
        if (artifact == null || artifact.payload() == null
                || !"passed".equals(artifact.payload().get("status"))) {
            return;
        }
        Object rawSteps = artifact.payload().get("passedSteps");
        if (!(rawSteps instanceof List<?> steps)) {
            return;
        }
        addIfPresent(steps, GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                GenerationCompletionEvidenceType.FAST_VALIDATION, evidence);
        addIfPresent(steps, GenerationExecutionPlan.ValidationStep.BUILD,
                GenerationCompletionEvidenceType.BUILD_VALIDATION, evidence);
        addIfPresent(steps, GenerationExecutionPlan.ValidationStep.EXPERT_CHECK,
                GenerationCompletionEvidenceType.EXPERT_VALIDATION, evidence);
    }

    private static void addIfPresent(
            List<?> steps,
            GenerationExecutionPlan.ValidationStep step,
            GenerationCompletionEvidenceType evidenceType,
            List<GenerationCompletionEvidence> evidence
    ) {
        if (steps.contains(step.name())) {
            evidence.add(GenerationCompletionEvidence.of(
                    evidenceType,
                    "verification_evidence",
                    "验证步骤 " + step.name() + " 已通过"));
        }
    }

    private static boolean diffChanged(GenerationArtifact diffSummary) {
        if (diffSummary == null || diffSummary.payload() == null) {
            return false;
        }
        Map<String, Object> payload = diffSummary.payload();
        return positive(payload.get("addedCount"))
                || positive(payload.get("modifiedCount"))
                || positive(payload.get("deletedCount"));
    }

    private static boolean positive(Object value) {
        return value instanceof Number number && number.intValue() > 0;
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
