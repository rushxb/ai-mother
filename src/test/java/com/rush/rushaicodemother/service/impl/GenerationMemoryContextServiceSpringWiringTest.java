package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.orchestration.context.AiContextPackAssembler;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextReadExecutor;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationMemoryContextServiceSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(GenerationTraceService.class, () -> mock(GenerationTraceService.class))
            .withBean(GenerationSemanticMemoryService.class,
                    () -> mock(GenerationSemanticMemoryService.class))
            .withBean(AiContextPackAssembler.class, () -> mock(AiContextPackAssembler.class))
            .withBean(GenerationMemoryContextProperties.class, GenerationMemoryContextProperties::new)
            .withBean(GenerationContextPreparationMetricsCollector.class,
                    () -> new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()))
            .withUserConfiguration(
                    GenerationMemoryContextReadExecutor.class,
                    GenerationMemoryContextServiceImpl.class
            );

    @Test
    void shouldCreateServiceUsingProductionConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GenerationMemoryContextReadExecutor.class);
            assertThat(context).hasSingleBean(GenerationMemoryContextServiceImpl.class);
        });
    }
}
