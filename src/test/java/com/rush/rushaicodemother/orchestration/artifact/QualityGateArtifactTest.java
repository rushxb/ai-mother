package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QualityGateArtifactTest {

    @Test
    void shouldRoundTripGateResultAndPreserveReviewDetails() {
        QualityGateResult result = QualityGateResult.passed(
                List.of("依赖版本建议升级"),
                List.of("生成规范已构建")
        );

        GenerationArtifact persisted = QualityGateArtifact.fromResult(
                result,
                Map.of("securityWarnings", List.of("依赖版本建议升级"))
        ).toArtifact("Review", "质量门禁");
        QualityGateArtifact restored = QualityGateArtifact.fromArtifact(persisted);

        assertEquals(result, restored.result());
        assertEquals(
                List.of("依赖版本建议升级"),
                persisted.payload().get("securityWarnings")
        );
    }

    @Test
    void blankReviewMessagesMustBeRejectedBeforePersistence() {
        QualityGateResult result = QualityGateResult.passed(
                List.of(" "),
                List.of("生成规范已构建")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> QualityGateArtifact.fromResult(result, Map.of())
        );
    }
}
