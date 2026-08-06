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
    void hardcodedDefaultsMustRemainBoundedForProduction() {
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
    void externalOverridesMustNotChangeFixedSessionLimits() {
        contextRunner
                .withPropertyValues(
                        "app.generation-session.lock-stripes=32",
                        "app.generation-session.max-tracked-sessions=250",
                        "app.generation-session.completed-replay-retention=45s",
                        "app.generation-session.cleanup-interval=3s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationSessionProperties properties = context.getBean(GenerationSessionProperties.class);
                    assertThat(properties.getLockStripes())
                            .isEqualTo(GenerationSessionProperties.LOCK_STRIPES);
                    assertThat(properties.getMaxTrackedSessions())
                            .isEqualTo(GenerationSessionProperties.MAX_TRACKED_SESSIONS);
                    assertThat(properties.getCompletedReplayRetention())
                            .isEqualTo(GenerationSessionProperties.COMPLETED_REPLAY_RETENTION);
                    assertThat(properties.getCleanupInterval())
                            .isEqualTo(GenerationSessionProperties.CLEANUP_INTERVAL);
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
}