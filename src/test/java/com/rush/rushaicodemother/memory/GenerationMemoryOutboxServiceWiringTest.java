package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.monitor.SemanticMemoryMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationMemoryOutboxServiceWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(GenerationMemoryOutboxRepository.class,
                    () -> mock(GenerationMemoryOutboxRepository.class))
            .withBean(GenerationSemanticMemoryService.class,
                    () -> mock(GenerationSemanticMemoryService.class))
            .withBean(GenerationMemoryOutboxProperties.class,
                    GenerationMemoryOutboxProperties::new)
            .withBean(SemanticMemoryMetricsCollector.class,
                    () -> new SemanticMemoryMetricsCollector(new SimpleMeterRegistry()))
            .withUserConfiguration(GenerationMemoryOutboxService.class);

    @Test
    void durableGenerationOutboxMustNotConsumeIntoOnlyProcessLocalMemory() {
        contextRunner.withPropertyValues(
                        "app.memory.long-term.enabled=false",
                        "app.memory.outbox.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(GenerationMemoryOutboxService.class));
    }

    @Test
    void durableGenerationOutboxMustRequireItsOwnEnablement() {
        contextRunner.withPropertyValues(
                        "app.memory.long-term.enabled=true",
                        "app.memory.outbox.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(GenerationMemoryOutboxService.class));
    }

    @Test
    void durableGenerationOutboxMustStartOnlyWithMilvusAndOutboxEnabled() {
        contextRunner.withPropertyValues(
                        "app.memory.long-term.enabled=true",
                        "app.memory.outbox.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(GenerationMemoryOutboxService.class));
    }
}
