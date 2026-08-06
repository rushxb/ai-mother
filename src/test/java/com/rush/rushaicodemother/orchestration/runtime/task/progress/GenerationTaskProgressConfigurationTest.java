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
    void hardcodedEtaAndProfileDefaultsMustRemainBoundedForProduction() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GenerationTaskProgressProperties properties =
                    context.getBean(GenerationTaskProgressProperties.class);
            assertThat(properties.getTaskSampleLimit())
                    .isEqualTo(GenerationTaskProgressProperties.TASK_SAMPLE_LIMIT);
            assertThat(properties.getSpanSampleLimit())
                    .isEqualTo(GenerationTaskProgressProperties.SPAN_SAMPLE_LIMIT);
            assertThat(properties.getMinimumHistoricalSamples())
                    .isEqualTo(GenerationTaskProgressProperties.MINIMUM_HISTORICAL_SAMPLES);
            assertThat(properties.getHighConfidenceSamples())
                    .isEqualTo(GenerationTaskProgressProperties.HIGH_CONFIDENCE_SAMPLES);
            assertThat(properties.getProfileCacheTtl())
                    .isEqualTo(GenerationTaskProgressProperties.PROFILE_CACHE_TTL);
            assertThat(properties.getFallbackTotalDuration())
                    .isEqualTo(GenerationTaskProgressProperties.FALLBACK_TOTAL_DURATION);
            assertThat(properties.getMaximumEstimatedDuration())
                    .isEqualTo(GenerationTaskProgressProperties.MAXIMUM_ESTIMATED_DURATION);
            assertThat(properties.getRunningProgressCap())
                    .isEqualTo(GenerationTaskProgressProperties.RUNNING_PROGRESS_CAP);
        });
    }

    /** 进度采样阈值固定为常量，外部配置不得改写。 */
    @Test
    void externalPropertiesMustNotOverrideHardcodedProgressThresholds() {
        contextRunner
                .withPropertyValues(
                        "app.generation-progress.minimum-historical-samples=50",
                        "app.generation-progress.high-confidence-samples=10",
                        "app.generation-progress.task-sample-limit=1000",
                        "app.generation-progress.running-progress-cap=50"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationTaskProgressProperties properties =
                            context.getBean(GenerationTaskProgressProperties.class);
                    assertThat(properties.getMinimumHistoricalSamples())
                            .isEqualTo(GenerationTaskProgressProperties.MINIMUM_HISTORICAL_SAMPLES);
                    assertThat(properties.getHighConfidenceSamples())
                            .isEqualTo(GenerationTaskProgressProperties.HIGH_CONFIDENCE_SAMPLES);
                    assertThat(properties.getTaskSampleLimit())
                            .isEqualTo(GenerationTaskProgressProperties.TASK_SAMPLE_LIMIT);
                    assertThat(properties.getRunningProgressCap())
                            .isEqualTo(GenerationTaskProgressProperties.RUNNING_PROGRESS_CAP);
                });
    }

    /**
     * 采样阈值与时长已不可外部注入，改为直接校验一致性约束本身，
     * 避免常量被误改成“高置信样本数低于最小历史样本数”或“上限低于回退时长”。
     */
    @Test
    void directValidationRejectsIncoherentThresholdsAndDurations() {
        GenerationTaskProgressProperties properties = new GenerationTaskProgressProperties();
        assertThat(properties.isConfigurationCoherent()).isTrue();

        properties.setMinimumHistoricalSamples(50);
        properties.setHighConfidenceSamples(10);
        assertThat(properties.isConfigurationCoherent()).isFalse();

        properties = new GenerationTaskProgressProperties();
        properties.setMaximumEstimatedDuration(Duration.ofMinutes(5));
        properties.setFallbackTotalDuration(Duration.ofMinutes(20));
        assertThat(properties.isConfigurationCoherent()).isFalse();

        properties = new GenerationTaskProgressProperties();
        properties.setProfileCacheTtl(Duration.ZERO);
        assertThat(properties.isConfigurationCoherent()).isFalse();
    }
}
