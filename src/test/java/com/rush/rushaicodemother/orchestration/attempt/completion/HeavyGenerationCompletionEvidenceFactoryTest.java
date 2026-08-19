package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeavyGenerationCompletionEvidenceFactoryTest {

    @Test
    void structuredArtifactsMustProduceCompleteExpertEvidence() {
        GenerationPreparation preparation = preparation(QualityGateResult.passed(List.of(), List.of("质量门禁通过")));
        preparation.putArtifact(artifact("requirements", Map.of("intent", "生成管理后台")));
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "expert_validation",
                        Set.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                                GenerationExecutionPlan.ValidationStep.BUILD,
                                GenerationExecutionPlan.ValidationStep.EXPERT_CHECK
                        ),
                        Map.of()
                ));
        GenerationExecutionContext context = executionContext();
        context.recordSuccessfulWorkspaceMutations(2);

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation, context));

        assertTrue(evidence.contains(GenerationCompletionEvidenceType.INTENT_COVERAGE));
        assertTrue(evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE));
        assertTrue(evidence.contains(GenerationCompletionEvidenceType.FAST_VALIDATION));
        assertTrue(evidence.contains(GenerationCompletionEvidenceType.BUILD_VALIDATION));
        assertTrue(evidence.contains(GenerationCompletionEvidenceType.EXPERT_VALIDATION));
    }

    @Test
    void diffSummaryWithPositiveCountMustCountAsWorkspaceChange() {
        GenerationPreparation preparation = preparation(null);
        preparation.putArtifact(artifact(DiffSummary.KEY, DiffSummary.created(
                1L,
                "heavy-completion-test",
                "D:/workspace/base",
                "D:/workspace/current",
                List.of(),
                List.of("src/App.vue"),
                List.of(),
                List.of("src/App.vue | 内容已变更")
        ).toPayload()));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertTrue(evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE));
    }

    @Test
    void skippedDiffWithStalePositiveCountMustNotCountAsWorkspaceChange() {
        GenerationPreparation preparation = preparation(null);
        Map<String, Object> skippedPayload = new LinkedHashMap<>();
        skippedPayload.put("schemaVersion", "v1");
        skippedPayload.put("provider", "local_snapshot");
        skippedPayload.put("status", "skipped");
        skippedPayload.put("appId", 1L);
        skippedPayload.put("taskId", "heavy-completion-test");
        skippedPayload.put("basePath", "D:/workspace/base");
        skippedPayload.put("currentPath", "D:/workspace/current");
        skippedPayload.put("addedCount", 1);
        skippedPayload.put("modifiedCount", 0);
        skippedPayload.put("deletedCount", 0);
        skippedPayload.put("addedFiles", List.of("src/App.vue"));
        skippedPayload.put("modifiedFiles", List.of());
        skippedPayload.put("deletedFiles", List.of());
        skippedPayload.put("modifiedDetails", List.of());
        skippedPayload.put("reason", "snapshot_unavailable");
        skippedPayload.put("createdAt", "2026-08-19T00:00:00");
        preparation.putArtifact(artifact("diff_summary", skippedPayload));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertFalse(evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE));
    }

    @Test
    void preGenerationQualityGateMustNotCountAsPostGenerationFastValidation() {
        GenerationPreparation preparation = preparation(
                QualityGateResult.passed(List.of(), List.of("生成前质量门禁通过")));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertFalse(evidence.contains(GenerationCompletionEvidenceType.FAST_VALIDATION));
    }

    @Test
    void verificationEvidenceForAnotherProjectTypeMustNotSatisfyCompletion() {
        GenerationPreparation preparation = preparation(null);
        preparation.putArtifact(artifact(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY,
                Map.of(
                        "status", "passed",
                        "source", "stale_backend_validation",
                        "targetType", CodeGenTypeEnum.BACKEND_PROJECT.getValue(),
                        "passedSteps", List.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK.name(),
                                GenerationExecutionPlan.ValidationStep.BUILD.name(),
                                GenerationExecutionPlan.ValidationStep.EXPERT_CHECK.name()
                        ),
                        "details", Map.of()
                )
        ));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertFalse(evidence.contains(GenerationCompletionEvidenceType.FAST_VALIDATION));
        assertFalse(evidence.contains(GenerationCompletionEvidenceType.BUILD_VALIDATION));
        assertFalse(evidence.contains(GenerationCompletionEvidenceType.EXPERT_VALIDATION));
    }

    @Test
    void zeroDiffMustNotCountAsWorkspaceChange() {
        GenerationPreparation preparation = preparation(null);
        preparation.putArtifact(artifact(DiffSummary.KEY, DiffSummary.created(
                1L,
                "heavy-completion-test",
                "D:/workspace/base",
                "D:/workspace/current",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ).toPayload()));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertFalse(evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE));
        assertFalse(evidence.contains(GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION));
    }

    @Test
    void onlyStructuredNonBlankNoChangeReasonMustCountAsJustification() {
        GenerationPreparation preparation = preparation(null);
        preparation.putArtifact(artifact("no_change_justification", Map.of(
                "reason", "现有实现已满足冻结意图，无需修改"
        )));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertTrue(evidence.contains(GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION));
    }

    private GenerationPreparation preparation(QualityGateResult qualityGateResult) {
        return new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "build",
                "测试任务",
                List.of(),
                new LinkedHashMap<>(),
                qualityGateResult,
                Map.of(),
                "heavy-completion-test"
        );
    }

    private GenerationArtifact artifact(String key, Map<String, Object> payload) {
        return GenerationArtifact.of(key, "Test", "测试制品", payload);
    }

    private GenerationExecutionContext executionContext() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        return new GenerationExecutionContext(
                "heavy-completion-test", 1L, 2L, now,
                new GenerationRuntimeProperties().toLimits(), Clock.fixed(now, ZoneOffset.UTC));
    }
}
