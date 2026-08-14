package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationVerificationEvidenceRecorderTest {

    @Test
    void recordPassedMustPersistOnlyObservedSteps() {
        GenerationPreparation preparation = preparation();

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
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
        assertEquals("passed", artifact.payload().get("status"));
        assertEquals("build_validation", artifact.payload().get("source"));
        assertEquals(List.of("FAST_CHECK", "BUILD"), artifact.payload().get("passedSteps"));
        assertEquals("vue_project", artifact.payload().get("targetType"));
        assertEquals(Map.of("component", "frontend", "stage", "done"), artifact.payload().get("details"));
        assertEquals(
                Set.of(
                        GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                        GenerationExecutionPlan.ValidationStep.BUILD),
                GenerationVerificationEvidenceRecorder.latestObservation(preparation)
                        .orElseThrow()
                        .passedSteps());
    }

    @Test
    void repeatedRecordingMustMergeStepsWithoutDuplicates() {
        GenerationPreparation preparation = preparation();
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "fast_validation",
                        Set.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK),
                        Map.of())
        );

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
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

}
