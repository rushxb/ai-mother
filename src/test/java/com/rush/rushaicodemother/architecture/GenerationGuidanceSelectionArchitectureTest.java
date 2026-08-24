package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止 Agent 节点重新解释 Prompt 并产生漂移的 recipe/skill 选择。 */
class GenerationGuidanceSelectionArchitectureTest {

    private static final Path AGENT_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "agent");

    @Test
    void plannerAndContextMustOnlyConsumeFrozenGuidanceSelection() throws Exception {
        List<Path> consumers = List.of(
                AGENT_SOURCE_ROOT.resolve("PlannerAgentNode.java"),
                AGENT_SOURCE_ROOT.resolve("ContextAgentNode.java"),
                AGENT_SOURCE_ROOT.resolve("ArchitectAgentNode.java")
        );

        for (Path consumer : consumers) {
            assertThat(Files.readString(consumer))
                    .as("%s 必须消费冻结的工程指引", consumer)
                    .contains("guidanceSelection()")
                    .doesNotContain("matchRecipes(", "matchSkills(");
        }
    }
}
