package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止规划消融图的组合逻辑重新散落回顶层编排器。 */
class GenerationPlanningGraphArchitectureTest {

    private static final Path ORCHESTRATOR_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "AgentGenerationOrchestrator.java");

    @Test
    void orchestratorMustDependOnlyOnThePlanningGraphRegistrySeam() throws Exception {
        String source = Files.readString(ORCHESTRATOR_SOURCE);

        assertThat(source)
                .contains("GenerationPlanningGraphRegistry")
                .contains("planningGraphRegistry.resolve(")
                .doesNotContain(
                        "CompactPlanningAgentNode",
                        "NoPlanningAgentNode",
                        "switch (planningVariant)",
                        "switch (request.planningVariant())"
                );
    }
}
