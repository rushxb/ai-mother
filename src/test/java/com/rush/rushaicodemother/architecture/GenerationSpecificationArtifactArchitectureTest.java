package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止生成规范的字段解释重新散落到规划、执行、恢复与完成判定链。 */
class GenerationSpecificationArtifactArchitectureTest {

    private static final Path ORCHESTRATION_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producersAndConsumersMustCrossTheTypedSpecificationArtifactBoundary() throws Exception {
        List<Path> sources = List.of(
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "CodeAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "NoPlanningAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "ReviewAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "BuildFixAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                        "create", "CreatePostGenerationValidationService.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve("AgentGenerationOrchestrator.java"),
                ORCHESTRATION_SOURCE_ROOT.resolve("GenerationPreparation.java"),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of(
                        "attempt", "completion", "HeavyGenerationCompletionEvidenceFactory.java"))
        );

        for (Path source : sources) {
            assertThat(Files.readString(source))
                    .as("%s 必须通过强类型生成规范边界读写", source)
                    .contains("GenerationSpecificationArtifact")
                    .doesNotContain("\"generation_spec\"");
        }
    }
}
