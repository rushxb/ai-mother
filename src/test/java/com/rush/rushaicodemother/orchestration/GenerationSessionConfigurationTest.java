package com.rush.rushaicodemother.orchestration;

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

class GenerationSessionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException exception) {
                    throw new UncheckedIOException("无法加载 application.yml", exception);
                }
            })
            .withUserConfiguration(GenerationSessionProperties.class);

    @Test
    void applicationYamlMustBindProductionDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GenerationSessionProperties properties = context.getBean(GenerationSessionProperties.class);
            assertThat(properties.getLockStripes()).isEqualTo(64);
            assertThat(properties.getMaxTrackedSessions()).isEqualTo(1_000);
            assertThat(properties.getCompletedReplayRetention()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getCleanupInterval()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void environmentStyleOverridesMustBindAllSessionLimits() {
        contextRunner
                .withPropertyValues(
                        "GENERATION_SESSION_LOCK_STRIPES=32",
                        "GENERATION_SESSION_MAX_TRACKED_SESSIONS=250",
                        "GENERATION_SESSION_COMPLETED_REPLAY_RETENTION=45s",
                        "GENERATION_SESSION_CLEANUP_INTERVAL=3s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationSessionProperties properties = context.getBean(GenerationSessionProperties.class);
                    assertThat(properties.getLockStripes()).isEqualTo(32);
                    assertThat(properties.getMaxTrackedSessions()).isEqualTo(250);
                    assertThat(properties.getCompletedReplayRetention()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.getCleanupInterval()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void registryBeanMustUseProductionConfigurationConstructor() {
        contextRunner
                .withUserConfiguration(GenerationSessionRegistry.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GenerationSessionRegistry.class);
                });
    }

    @Test
    void unsafeCleanupTimingMustFailContextStartup() {
        contextRunner
                .withPropertyValues(
                        "GENERATION_SESSION_COMPLETED_REPLAY_RETENTION=10s",
                        "GENERATION_SESSION_CLEANUP_INTERVAL=11s"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.generation-session");
                });
    }
}