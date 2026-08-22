package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止项目上下文恢复和解释逻辑重新散落到 Agent 节点。 */
class ContextSummaryArtifactArchitectureTest {

    private static final Path ORCHESTRATION_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void producerRecoveryAndConsumersMustCrossTypedContextSummaryArtifact() throws Exception {
        List<Path> sources = List.of(
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "ContextAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "ArchitectAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "CodeAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("agent", "NoPlanningAgentNode.java")),
                ORCHESTRATION_SOURCE_ROOT.resolve("AgentGenerationOrchestrator.java"),
                ORCHESTRATION_SOURCE_ROOT.resolve(Path.of("dag", "GenerationAgentContext.java"))
        );

        for (Path source : sources) {
            assertThat(Files.readString(source))
                    .as("%s 必须通过强类型项目上下文制品读写", source)
                    .contains("ContextSummaryArtifact")
                    .doesNotContain("\"context_summary\"");
        }
    }
}
