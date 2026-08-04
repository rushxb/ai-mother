package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationVerificationEvidenceRecorderTest {

    @Test
    void recordPassedMustPersistStepsForPlannedLevel() {
        GenerationPreparation preparation = preparation();

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                GenerationVerificationPolicy.planned(graph(ExpectedValidationLevel.BUILD)),
                "build_validation"
        );

        GenerationArtifact artifact = preparation.artifact(
                GenerationVerificationEvidenceRecorder.ARTIFACT_KEY);
        assertEquals("passed", artifact.payload().get("status"));
        assertEquals("build_validation", artifact.payload().get("source"));
        assertEquals(List.of("FAST_CHECK", "BUILD"), artifact.payload().get("passedSteps"));
    }

    @Test
    void repeatedRecordingMustMergeStepsWithoutDuplicates() {
        GenerationPreparation preparation = preparation();
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                GenerationVerificationPolicy.planned(graph(ExpectedValidationLevel.FAST)),
                "fast_validation"
        );

        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                GenerationVerificationPolicy.planned(graph(ExpectedValidationLevel.EXPERT)),
                "expert_validation"
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

    private GenerationExecutionPlan.ValidationGraph graph(ExpectedValidationLevel level) {
        return GenerationExecutionPlan.ValidationGraph.forLevel(level);
    }
}
