package com.rush.rushaicodemother.orchestration.runtime.task;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTaskExecutorConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException exception) {
                    throw new UncheckedIOException("Unable to load application.yml", exception);
                }
            })
            .withUserConfiguration(
                    GenerationTaskExecutorProperties.class,
                    ScheduledGenerationTaskWatchdog.class,
                    VirtualThreadGenerationTaskExecutor.class
            );

    @Test
    void applicationYamlMustBindBoundedProductionDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GenerationTaskExecutorProperties properties =
                    context.getBean(GenerationTaskExecutorProperties.class);
            assertThat(properties.getMaxConcurrency()).isEqualTo(4);
            assertThat(properties.getQueueCapacity()).isEqualTo(32);
            assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(context).hasSingleBean(GenerationTaskExecutor.class);
            assertThat(context).hasSingleBean(GenerationTaskWatchdog.class);
        });
    }

    @Test
    void environmentStyleOverridesMustBindAllExecutorLimits() {
        contextRunner
                .withPropertyValues(
                        "GENERATION_TASK_MAX_CONCURRENCY=8",
                        "GENERATION_TASK_QUEUE_CAPACITY=64",
                        "GENERATION_TASK_EXECUTOR_SHUTDOWN_TIMEOUT=5s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationTaskExecutorProperties properties =
                            context.getBean(GenerationTaskExecutorProperties.class);
                    assertThat(properties.getMaxConcurrency()).isEqualTo(8);
                    assertThat(properties.getQueueCapacity()).isEqualTo(64);
                    assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void invalidExecutorLimitsMustFailApplicationContextStartup() {
        contextRunner
                .withPropertyValues("GENERATION_TASK_MAX_CONCURRENCY=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("app.generation-task-executor");
                });
    }
}
