package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止完成门禁与记录器重新各自解释验证制品的动态字段。 */
class GenerationVerificationEvidenceArtifactArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void recorderMustOwnPersistenceThroughTheTypedArtifact() throws Exception {
        String recorder = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "verification", "GenerationVerificationEvidenceRecorder.java")));

        assertThat(recorder)
                .contains("GenerationVerificationEvidenceArtifact.fromArtifact(")
                .contains(".fromObservation(observation, preparation.targetType())")
                .contains("evidence.toArtifact()")
                .doesNotContain("payload().get(")
                .doesNotContain("\"verification_evidence\"");
    }

    @Test
    void completionGateMustConsumeAValidatedObservationInsteadOfArtifactFields() throws Exception {
        String completionFactory = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "attempt", "completion", "HeavyGenerationCompletionEvidenceFactory.java")));

        assertThat(completionFactory)
                .contains("GenerationVerificationEvidenceRecorder.latestObservation(preparation)")
                .doesNotContain("payload().get(\"passedSteps\")")
                .doesNotContain("\"verification_evidence\"");
    }
}
