package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationDagRunnerSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(GenerationOrchestrationTaskStore.class,
                    () -> mock(GenerationOrchestrationTaskStore.class))
            .withBean(GenerationExecutionContextService.class,
                    () -> mock(GenerationExecutionContextService.class))
            .withBean(GenerationOrchestrationMetricsCollector.class,
                    () -> new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()))
            .withBean(GenerationTaskSnapshotProperties.class, GenerationTaskSnapshotProperties::new)
            .withUserConfiguration(GenerationDagRunner.class);

    @Test
    void shouldCreateRunnerUsingProductionConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenerationDagRunner.class);
        });
    }
}
