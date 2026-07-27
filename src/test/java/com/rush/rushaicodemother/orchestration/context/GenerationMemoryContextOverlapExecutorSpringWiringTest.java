package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationMemoryContextOverlapExecutorSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(GenerationMemoryContextProperties.class, GenerationMemoryContextProperties::new)
            .withBean(GenerationContextPreparationMetricsCollector.class,
                    () -> new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()))
            .withBean(GenerationExecutionContextService.class,
                    () -> mock(GenerationExecutionContextService.class))
            .withUserConfiguration(GenerationMemoryContextOverlapExecutor.class);

    @Test
    void shouldCreateOverlapExecutorWithProductionDependencies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenerationMemoryContextOverlapExecutor.class);
        });
    }
}
