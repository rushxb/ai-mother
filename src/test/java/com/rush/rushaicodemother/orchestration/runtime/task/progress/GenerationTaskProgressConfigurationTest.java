package com.rush.rushaicodemother.orchestration.runtime.task.progress;

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

class GenerationTaskProgressConfigurationTest {

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
            .withUserConfiguration(GenerationTaskProgressProperties.class);

    @Test
    void applicationYamlMustBindBoundedEtaAndProfileDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GenerationTaskProgressProperties properties =
                    context.getBean(GenerationTaskProgressProperties.class);
            assertThat(properties.getTaskSampleLimit()).isEqualTo(200);
            assertThat(properties.getSpanSampleLimit()).isEqualTo(5_000);
            assertThat(properties.getMinimumHistoricalSamples()).isEqualTo(8);
            assertThat(properties.getHighConfidenceSamples()).isEqualTo(30);
            assertThat(properties.getProfileCacheTtl()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.getFallbackTotalDuration()).isEqualTo(Duration.ofMinutes(20));
            assertThat(properties.getMaximumEstimatedDuration()).isEqualTo(Duration.ofHours(2));
            assertThat(properties.getRunningProgressCap()).isEqualTo(95);
        });
    }

    @Test
    void invalidThresholdOrderingMustFailStartup() {
        contextRunner
                .withPropertyValues(
                        "app.generation-progress.minimum-historical-samples=50",
                        "app.generation-progress.high-confidence-samples=10"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("app.generation-progress");
                });
    }
}
