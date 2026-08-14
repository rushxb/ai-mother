package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeavyGenerationCompletionEvidenceFactoryTest {

    @Test
    void structuredArtifactsMustProduceCompleteExpertEvidence() {
        GenerationPreparation preparation = preparation(QualityGateResult.passed(List.of(), List.of("质量门禁通过")));
        preparation.putArtifact(artifact("requirements", Map.of("intent", "生成管理后台")));
        preparation.putArtifact(artifact(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY,
                Map.of(
                        "status", "passed",
                        "passedSteps", List.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK.name(),
                                GenerationExecutionPlan.ValidationStep.BUILD.name(),
                                GenerationExecutionPlan.ValidationStep.EXPERT_CHECK.name()
                        )
                )
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
        preparation.putArtifact(artifact("diff_summary", Map.of(
                "addedCount", 0,
                "modifiedCount", 1,
                "deletedCount", 0
        )));

        GenerationCompletionEvidenceSet evidence = HeavyGenerationCompletionEvidenceFactory.collect(
                preparation, new GenerationSession(preparation));

        assertTrue(evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE));
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
    void zeroDiffMustNotCountAsWorkspaceChange() {
        GenerationPreparation preparation = preparation(null);
        preparation.putArtifact(artifact("diff_summary", Map.of(
                "addedCount", 0,
                "modifiedCount", 0,
                "deletedCount", 0
        )));

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
