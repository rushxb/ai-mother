package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止任一生成模式恢复为原始仓库文本直接入模。 */
class RepositoryContextTrustArchitectureTest {

    private static final Path ORCHESTRATION_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration");

    @Test
    void everyRepositoryAwareGenerationModeMustUseTheSharedTrustBoundary() throws Exception {
        List<Path> consumers = List.of(
                ORCHESTRATION_ROOT.resolve(Path.of("readonly", "ReadOnlyAnalysisService.java")),
                ORCHESTRATION_ROOT.resolve(Path.of("edit", "LightweightEditContextAssembler.java")),
                ORCHESTRATION_ROOT.resolve(Path.of("edit", "AgentEditRepositoryContextAssembler.java")),
                ORCHESTRATION_ROOT.resolve(Path.of("agent", "ContextAgentNode.java"))
        );

        for (Path consumer : consumers) {
            assertThat(Files.readString(consumer))
                    .as("%s 必须消费统一项目上下文信任边界", consumer)
                    .contains("RepositoryContextTrustService")
                    .contains("RepositoryContextRequest.forPurpose(");
        }

        assertThat(Files.readString(consumers.getLast()))
                .doesNotContain("contextBoundaryService.protectRepositoryContext(");
    }
}
