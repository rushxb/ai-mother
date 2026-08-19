package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止质量门禁的字段解释和缺失恢复策略重新散落到生产与恢复链。 */
class QualityGateArtifactArchitectureTest {

    private static final Path ORCHESTRATION_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producerAndCheckpointRestoreMustCrossTypedQualityGateArtifact() throws Exception {
        List<Path> sources = List.of(
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "ReviewAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("dag", "GenerationAgentContext.java"))
        );

        for (Path source : sources) {
            assertThat(Files.readString(source))
                    .as("%s 必须通过强类型质量门禁制品读写", source)
                    .contains("QualityGateArtifact")
                    .doesNotContain("\"quality_gate\"");
        }
    }

    @Test
    void reviewedPlanningVariantsMustNotAcceptMissingGateResult() throws Exception {
        Path orchestrator = ORCHESTRATION_SOURCE_ROOT.resolve("AgentGenerationOrchestrator.java");

        assertThat(Files.readString(orchestrator))
                .contains("requireQualityGateResult(request, context)")
                .contains("GenerationPlanningVariant.NO_PLAN");
    }
}
