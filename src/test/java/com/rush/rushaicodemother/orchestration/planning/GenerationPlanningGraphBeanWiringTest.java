package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.agent.ArchitectAgentNode;
import com.rush.rushaicodemother.orchestration.agent.BuildFixAgentNode;
import com.rush.rushaicodemother.orchestration.agent.CodeAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.PlannerAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ReviewAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationPlanningGraphBeanWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PlannerAgentNode.class, () -> mock(PlannerAgentNode.class))
            .withBean(TemplateAgentNode.class, () -> mock(TemplateAgentNode.class))
            .withBean(ContextAgentNode.class, () -> mock(ContextAgentNode.class))
            .withBean(ArchitectAgentNode.class, () -> mock(ArchitectAgentNode.class))
            .withBean(CodeAgentNode.class, () -> mock(CodeAgentNode.class))
            .withBean(ReviewAgentNode.class, () -> mock(ReviewAgentNode.class))
            .withBean(BuildFixAgentNode.class, () -> mock(BuildFixAgentNode.class))
            .withUserConfiguration(
                    CurrentDagPlanningGraphAdapter.class,
                    CompactPlanningGraphAdapter.class,
                    NoPlanningGraphAdapter.class,
                    GenerationPlanningGraphRegistry.class
            );

    @Test
    void shouldRegisterEveryPlanningVariantWithoutAmbiguousAdapters() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenerationPlanningGraphRegistry.class);
            assertThat(context).getBeans(GenerationPlanningGraphAdapter.class).hasSize(3);
        });
    }
}
