package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationVerificationEvidenceRecorderTest {

    @Test
    void recordPassedMustPersistOnlyObservedSteps() {
        GenerationPreparation preparation = preparation();
        GenerationSession session = session(preparation);

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                session,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "build_validation",
                        Set.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                                GenerationExecutionPlan.ValidationStep.BUILD),
                        Map.of("component", "frontend", "stage", "done"))
        );

        GenerationArtifact artifact = preparation.artifact(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY);
        assertEquals("v2", artifact.payload().get("schemaVersion"));
        assertEquals("passed", artifact.payload().get("status"));
        assertEquals("build_validation", artifact.payload().get("source"));
        assertEquals(1L, artifact.payload().get("appId"));
        assertEquals("verification-evidence-test", artifact.payload().get("taskId"));
        assertEquals(7L, artifact.payload().get("executionEpoch"));
        assertEquals(List.of("FAST_CHECK", "BUILD"), artifact.payload().get("passedSteps"));
        assertEquals("vue_project", artifact.payload().get("targetType"));
        assertEquals(Map.of("component", "frontend", "stage", "done"), artifact.payload().get("details"));
        assertEquals(
                Set.of(
                        GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                        GenerationExecutionPlan.ValidationStep.BUILD),
                GenerationVerificationEvidenceRecorder.latestObservation(preparation, session)
                        .orElseThrow()
                        .passedSteps());
    }

    @Test
    void repeatedRecordingMustMergeStepsWithoutDuplicates() {
        GenerationPreparation preparation = preparation();
        GenerationSession session = session(preparation);
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                session,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "fast_validation",
                        Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                        Map.of())
        );

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                session,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "expert_validation",
                        Set.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                                GenerationExecutionPlan.ValidationStep.BUILD,
                                GenerationExecutionPlan.ValidationStep.EXPERT_CHECK),
                        Map.of())
        );

        assertEquals(
                List.of("FAST_CHECK", "BUILD", "EXPERT_CHECK"),
                preparation.artifact(GenerationVerificationEvidenceRecorder.ARTIFACT_KEY)
                        .payload().get("passedSteps")
        );
    }

    @Test
    void newExecutionEpochMustNotInheritPassedStepsFromStaleEvidence() {
        GenerationPreparation preparation = preparation();
        GenerationSession firstEpoch = session(preparation, 7L);
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                firstEpoch,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "first_epoch_expert_validation",
                        Set.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                                GenerationExecutionPlan.ValidationStep.BUILD,
                                GenerationExecutionPlan.ValidationStep.EXPERT_CHECK),
                        Map.of())
        );

        GenerationSession nextEpoch = session(preparation, 8L);
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                nextEpoch,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "next_epoch_fast_validation",
                        Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                        Map.of())
        );

        GenerationArtifact evidence = preparation.artifact(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY);
        assertEquals(8L, evidence.payload().get("executionEpoch"));
        assertEquals(List.of("FAST_CHECK"), evidence.payload().get("passedSteps"));
        assertEquals(
                Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                GenerationVerificationEvidenceRecorder.latestObservation(preparation, nextEpoch)
                        .orElseThrow()
                        .passedSteps()
        );
    }

    @Test
    void malformedCheckpointMustNotPoisonAFreshObservedValidation() {
        GenerationPreparation preparation = preparation();
        GenerationSession session = session(preparation);
        preparation.putArtifact(GenerationArtifact.of(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY,
                "Verification",
                "损坏的历史验证证据",
                Map.of(
                        "status", "passed",
                        "source", "legacy_validation",
                        "targetType", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                        "passedSteps", List.of("UNKNOWN_STEP"),
                        "details", Map.of()
                )
        ));

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                session,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "fresh_fast_validation",
                        Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                        Map.of("fresh", true)
                )
        );

        GenerationValidationObservation restored =
                GenerationVerificationEvidenceRecorder.latestObservation(preparation, session)
                        .orElseThrow();
        assertEquals(Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                restored.passedSteps());
        assertEquals("fresh_fast_validation", restored.source());
        assertEquals(Map.of("fresh", true), restored.details());
        assertEquals(
                List.of("FAST_CHECK"),
                preparation.artifact(GenerationVerificationEvidenceRecorder.ARTIFACT_KEY)
                        .payload().get("passedSteps")
        );
    }

    @Test
    void observationForAnotherProjectTypeMustBeRejectedBeforeCheckpointMutation() {
        GenerationPreparation preparation = preparation();
        GenerationSession session = session(preparation);

        assertThrows(
                IllegalArgumentException.class,
                () -> GenerationVerificationEvidenceRecorder.recordPassed(
                        preparation,
                        session,
                        GenerationValidationObservation.passed(
                                CodeGenTypeEnum.BACKEND_PROJECT,
                                "wrong_target_validation",
                                Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                                Map.of()
                        )
                )
        );

        assertNull(preparation.artifact(GenerationVerificationEvidenceRecorder.ARTIFACT_KEY));
    }

    private GenerationPreparation preparation() {
        return new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "build",
                "测试任务",
                List.of(),
                new LinkedHashMap<>(),
                null,
                Map.of(),
                "verification-evidence-test"
        );
    }

    private GenerationSession session(GenerationPreparation preparation) {
        return session(preparation, 7L);
    }

    private GenerationSession session(GenerationPreparation preparation, long executionEpoch) {
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        GenerationExecutionContext context = new GenerationExecutionContext(
                preparation.taskId(),
                1L,
                2L,
                now,
                new GenerationRuntimeProperties().toLimits(),
                Clock.fixed(now, ZoneOffset.UTC)
        );
        context.bindExecutionFence(new GenerationExecutionFence(
                preparation.taskId(), "worker-a", executionEpoch));
        return new GenerationSession(preparation, context);
    }

}
