package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止需求制品的字段解析重新散落到 Planner、DAG 恢复与执行消费者。 */
class GenerationRequirementsArtifactArchitectureTest {

    private static final Path ORCHESTRATION_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producerAndConsumersMustCrossTheTypedRequirementsArtifactBoundary() throws Exception {
        List<Path> sources = List.of(
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "PlannerAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "CodeAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("dag", "GenerationAgentContext.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                        "attempt", "completion", "HeavyGenerationCompletionEvidenceFactory.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve("GenerationPreparation.java")
        );

        for (Path source : sources) {
            assertThat(Files.readString(source))
                    .as("%s 必须通过强类型需求制品边界读写", source)
                    .contains("GenerationRequirementsArtifact")
                    .doesNotContain("\"requirements\"");
        }
    }

    @Test
    void completionEvidenceMustUseTheRequirementsDomainInvariant() throws Exception {
        Path completionEvidenceFactory = ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                "attempt", "completion", "HeavyGenerationCompletionEvidenceFactory.java"));

        assertThat(Files.readString(completionEvidenceFactory))
                .contains(
                        "GenerationRequirementsArtifact",
                        ".fromArtifact",
                        "provesIntentCoverage")
                .doesNotContain("artifact.payload().isEmpty()");
    }
}
