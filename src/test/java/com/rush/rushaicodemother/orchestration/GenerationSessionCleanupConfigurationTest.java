package com.rush.rushaicodemother.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationSessionCleanupConfigurationTest {

    @Test
    void configurationMustRegisterExactlyOneFixedDelayCleanupTask() {
        GenerationSessionProperties properties = new GenerationSessionProperties();
        properties.setCleanupInterval(Duration.ofSeconds(7));
        GenerationSessionRegistry registry = new GenerationSessionRegistry(properties);
        GenerationSessionCleanupConfiguration configuration =
                new GenerationSessionCleanupConfiguration(registry, properties);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        configuration.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).singleElement().satisfies(task -> {
            IntervalTask fixedDelayTask = (IntervalTask) task;
            assertThat(fixedDelayTask.getIntervalDuration()).isEqualTo(Duration.ofSeconds(7));
        });
        assertThat(registrar.getCronTaskList()).isEmpty();
        assertThat(registrar.getFixedRateTaskList()).isEmpty();
        assertThat(registrar.getTriggerTaskList()).isEmpty();
    }
}