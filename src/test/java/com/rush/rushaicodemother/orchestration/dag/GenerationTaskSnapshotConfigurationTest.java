package com.rush.rushaicodemother.orchestration.dag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTaskSnapshotConfigurationTest {

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
            .withUserConfiguration(GenerationTaskSnapshotProperties.class);

    @Test
    void applicationYamlMustBindNestedDefaultDirectory() {
        Path baseDirectory = Path.of("target", "test-workspaces", "snapshot-config-default")
                .toAbsolutePath()
                .normalize();

        contextRunner
                .withPropertyValues("code.base-dir=" + propertyPath(baseDirectory))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationTaskSnapshotProperties properties =
                            context.getBean(GenerationTaskSnapshotProperties.class);
                    assertThat(properties.getRootDirectory())
                            .isEqualTo(baseDirectory.resolve("tmp").resolve("orchestration_tasks"));
                    assertThat(properties.getMaxSnapshotBytes()).isEqualTo(2 * 1024 * 1024);
                    assertThat(properties.getMaxSnapshotsPerApp()).isEqualTo(100);
                    assertThat(properties.getRetention()).isEqualTo(Duration.ofDays(7));
                    assertThat(properties.getLockStripes()).isEqualTo(64);
                    assertThat(properties.isReplaySafeStartCheckpointElisionEnabled()).isFalse();
                    assertThat(properties.isReplaySafeCompletionCheckpointCoalescingEnabled()).isFalse();
                    assertThat(properties.getReplaySafeCompletionCheckpointInterval()).isEqualTo(4);
                });
    }

    @Test
    void environmentStyleOverridesMustBindAllSnapshotLimits() {
        Path snapshotDirectory = Path.of("target", "test-workspaces", "snapshot-config-override")
                .toAbsolutePath()
                .normalize();

        contextRunner
                .withPropertyValues(
                        "GENERATION_TASK_SNAPSHOT_ENABLED=false",
                        "GENERATION_REPLAY_SAFE_START_CHECKPOINT_ELISION_ENABLED=true",
                        "GENERATION_REPLAY_SAFE_COMPLETION_CHECKPOINT_COALESCING_ENABLED=true",
                        "GENERATION_REPLAY_SAFE_COMPLETION_CHECKPOINT_INTERVAL=8",
                        "GENERATION_TASK_SNAPSHOT_ROOT_DIRECTORY=" + propertyPath(snapshotDirectory),
                        "GENERATION_TASK_SNAPSHOT_MAX_BYTES=65536",
                        "GENERATION_TASK_SNAPSHOT_MAX_PER_APP=12",
                        "GENERATION_TASK_SNAPSHOT_RETENTION=36h",
                        "GENERATION_TASK_SNAPSHOT_LOCK_STRIPES=16"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationTaskSnapshotProperties properties =
                            context.getBean(GenerationTaskSnapshotProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.isReplaySafeStartCheckpointElisionEnabled()).isTrue();
                    assertThat(properties.isReplaySafeCompletionCheckpointCoalescingEnabled()).isTrue();
                    assertThat(properties.getReplaySafeCompletionCheckpointInterval()).isEqualTo(8);
                    assertThat(properties.getRootDirectory()).isEqualTo(snapshotDirectory);
                    assertThat(properties.getMaxSnapshotBytes()).isEqualTo(65_536);
                    assertThat(properties.getMaxSnapshotsPerApp()).isEqualTo(12);
                    assertThat(properties.getRetention()).isEqualTo(Duration.ofHours(36));
                    assertThat(properties.getLockStripes()).isEqualTo(16);
                });
    }

    @Test
    void invalidSnapshotLimitsMustFailApplicationContextStartup() {
        contextRunner
                .withPropertyValues("GENERATION_TASK_SNAPSHOT_MAX_BYTES=1024")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("app.generation-task-snapshot");
                });
    }

    @Test
    void completionCoalescingWithoutStartElisionMustFailApplicationContextStartup() {
        contextRunner
                .withPropertyValues(
                        "GENERATION_REPLAY_SAFE_COMPLETION_CHECKPOINT_COALESCING_ENABLED=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("app.generation-task-snapshot");
                });
    }

    private String propertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
