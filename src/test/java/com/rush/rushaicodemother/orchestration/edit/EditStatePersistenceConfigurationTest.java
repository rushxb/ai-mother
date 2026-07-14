package com.rush.rushaicodemother.orchestration.edit;

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

class EditStatePersistenceConfigurationTest {

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
            .withUserConfiguration(EditStatePersistenceProperties.class);

    @Test
    void applicationYamlMustBindNestedDefaultDirectory() {
        Path baseDirectory = Path.of("target", "test-workspaces", "edit-state-config-default")
                .toAbsolutePath()
                .normalize();

        contextRunner
                .withPropertyValues("code.base-dir=" + propertyPath(baseDirectory))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EditStatePersistenceProperties properties =
                            context.getBean(EditStatePersistenceProperties.class);
                    assertThat(properties.getRootDirectory())
                            .isEqualTo(baseDirectory.resolve("tmp").resolve("edit_state"));
                    assertThat(properties.getMaxCacheEntries()).isEqualTo(1_000);
                    assertThat(properties.getCacheExpireAfterAccess()).isEqualTo(Duration.ofHours(2));
                    assertThat(properties.getStateRetention()).isEqualTo(Duration.ofHours(24));
                    assertThat(properties.getMaxPersistedApps()).isEqualTo(10_000);
                    assertThat(properties.getLockStripes()).isEqualTo(64);
                });
    }

    @Test
    void environmentStyleOverridesMustBindAllEditStateLimits() {
        Path stateDirectory = Path.of("target", "test-workspaces", "edit-state-config-override")
                .toAbsolutePath()
                .normalize();

        contextRunner
                .withPropertyValues(
                        "EDIT_STATE_ENABLED=false",
                        "EDIT_STATE_ROOT_DIRECTORY=" + propertyPath(stateDirectory),
                        "EDIT_STATE_MAX_CACHE_ENTRIES=25",
                        "EDIT_STATE_CACHE_EXPIRE_AFTER_ACCESS=30m",
                        "EDIT_STATE_RETENTION=12h",
                        "EDIT_STATE_MAX_PERSISTED_APPS=500",
                        "EDIT_STATE_MAX_FILE_BYTES=65536",
                        "EDIT_STATE_MAX_RECENT_EDITS=12",
                        "EDIT_STATE_MAX_RECENT_FILES=30",
                        "EDIT_STATE_MAX_RECENT_VALIDATIONS=10",
                        "EDIT_STATE_MAX_TASK_ID_LENGTH=64",
                        "EDIT_STATE_MAX_FILE_PATH_LENGTH=512",
                        "EDIT_STATE_LOCK_STRIPES=16"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EditStatePersistenceProperties properties =
                            context.getBean(EditStatePersistenceProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getRootDirectory()).isEqualTo(stateDirectory);
                    assertThat(properties.getMaxCacheEntries()).isEqualTo(25);
                    assertThat(properties.getCacheExpireAfterAccess()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(properties.getStateRetention()).isEqualTo(Duration.ofHours(12));
                    assertThat(properties.getMaxPersistedApps()).isEqualTo(500);
                    assertThat(properties.getMaxStateFileBytes()).isEqualTo(65_536);
                    assertThat(properties.getMaxRecentEdits()).isEqualTo(12);
                    assertThat(properties.getMaxRecentFiles()).isEqualTo(30);
                    assertThat(properties.getMaxRecentValidations()).isEqualTo(10);
                    assertThat(properties.getMaxTaskIdLength()).isEqualTo(64);
                    assertThat(properties.getMaxFilePathLength()).isEqualTo(512);
                    assertThat(properties.getLockStripes()).isEqualTo(16);
                });
    }

    @Test
    void cacheExpiryBeyondRetentionMustFailApplicationContextStartup() {
        contextRunner
                .withPropertyValues(
                        "EDIT_STATE_CACHE_EXPIRE_AFTER_ACCESS=48h",
                        "EDIT_STATE_RETENTION=24h"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.edit-state");
                });
    }

    private String propertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
