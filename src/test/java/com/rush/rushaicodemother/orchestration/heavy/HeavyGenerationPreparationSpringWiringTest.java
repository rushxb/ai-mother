package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextOverlapExecutor;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentAssembler;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HeavyGenerationPreparationSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(HeavyGenerationIntentAssembler.class,
                    () -> mock(HeavyGenerationIntentAssembler.class))
            .withBean(GenerationMemoryContextService.class,
                    () -> mock(GenerationMemoryContextService.class))
            .withBean(GenerationOrchestrator.class,
                    () -> mock(GenerationOrchestrator.class))
            .withBean(GenerationToolExecutionContextService.class,
                    () -> mock(GenerationToolExecutionContextService.class))
            .withBean(GenerationWorkspaceService.class,
                    () -> mock(GenerationWorkspaceService.class))
            .withBean(GenerationMemoryContextOverlapExecutor.class,
                    () -> mock(GenerationMemoryContextOverlapExecutor.class))
            .withUserConfiguration(HeavyGenerationPreparationService.class);

    @Test
    void shouldCreatePreparationServiceWithProductionDependencies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HeavyGenerationPreparationService.class);
        });
    }
}
