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
                    assertThat(properties.getMaxCacheEntries())
                            .isEqualTo(EditStatePersistenceProperties.MAX_CACHE_ENTRIES);
                    assertThat(properties.getCacheExpireAfterAccess())
                            .isEqualTo(EditStatePersistenceProperties.CACHE_EXPIRE_AFTER_ACCESS);
                    assertThat(properties.getStateRetention())
                            .isEqualTo(EditStatePersistenceProperties.STATE_RETENTION);
                    assertThat(properties.getMaxPersistedApps())
                            .isEqualTo(EditStatePersistenceProperties.MAX_PERSISTED_APPS);
                    assertThat(properties.getLockStripes())
                            .isEqualTo(EditStatePersistenceProperties.LOCK_STRIPES);
                });
    }

    /** 编辑状态开关与落盘目录仍需支持部署期注入。 */
    @Test
    void environmentStyleOverridesMustBindEditStateToggleAndRootDirectory() {
        Path stateDirectory = Path.of("target", "test-workspaces", "edit-state-config-override")
                .toAbsolutePath()
                .normalize();

        contextRunner
                .withPropertyValues(
                        "EDIT_STATE_ENABLED=false",
                        "EDIT_STATE_ROOT_DIRECTORY=" + propertyPath(stateDirectory)
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EditStatePersistenceProperties properties =
                            context.getBean(EditStatePersistenceProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getRootDirectory()).isEqualTo(stateDirectory);
                });
    }

    /**
     * 编辑状态的缓存容量、保留期限和长度上限的 yaml 键已删除，
     * 历史环境变量名不再具备任何改写能力。
     */
    @Test
    void retiredEnvironmentVariablesMustNotChangeFixedEditStateLimits() {
        contextRunner
                .withPropertyValues(
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
                    assertThat(properties.getMaxCacheEntries())
                            .isEqualTo(EditStatePersistenceProperties.MAX_CACHE_ENTRIES);
                    assertThat(properties.getCacheExpireAfterAccess())
                            .isEqualTo(EditStatePersistenceProperties.CACHE_EXPIRE_AFTER_ACCESS);
                    assertThat(properties.getStateRetention())
                            .isEqualTo(EditStatePersistenceProperties.STATE_RETENTION);
                    assertThat(properties.getMaxPersistedApps())
                            .isEqualTo(EditStatePersistenceProperties.MAX_PERSISTED_APPS);
                    assertThat(properties.getMaxStateFileBytes())
                            .isEqualTo(EditStatePersistenceProperties.DEFAULT_MAX_STATE_FILE_BYTES);
                    assertThat(properties.getMaxRecentEdits())
                            .isEqualTo(EditStatePersistenceProperties.MAX_RECENT_EDITS);
                    assertThat(properties.getMaxRecentFiles())
                            .isEqualTo(EditStatePersistenceProperties.MAX_RECENT_FILES);
                    assertThat(properties.getMaxRecentValidations())
                            .isEqualTo(EditStatePersistenceProperties.MAX_RECENT_VALIDATIONS);
                    assertThat(properties.getMaxTaskIdLength())
                            .isEqualTo(EditStatePersistenceProperties.MAX_TASK_ID_LENGTH);
                    assertThat(properties.getMaxFilePathLength())
                            .isEqualTo(EditStatePersistenceProperties.MAX_FILE_PATH_LENGTH);
                    assertThat(properties.getLockStripes())
                            .isEqualTo(EditStatePersistenceProperties.LOCK_STRIPES);
                });
    }

    /** 该类仍保留 {@code @ConfigurationProperties}，规范键注入的越界组合必须继续拦截在启动期。 */
    @Test
    void cacheExpiryBeyondRetentionMustFailApplicationContextStartup() {
        contextRunner
                .withPropertyValues(
                        "app.edit-state.cache-expire-after-access=48h",
                        "app.edit-state.state-retention=24h"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("app.edit-state");
                });
    }

    /** 缓存期限不得超过保留期限的约束本身也直接校验，避免常量被误改。 */
    @Test
    void directValidationRejectsCacheExpiryBeyondRetention() {
        EditStatePersistenceProperties properties = new EditStatePersistenceProperties();
        assertThat(properties.isStorageConfigurationValid()).isTrue();

        properties.setCacheExpireAfterAccess(Duration.ofHours(48));
        properties.setStateRetention(Duration.ofHours(24));
        assertThat(properties.isStorageConfigurationValid()).isFalse();
    }

    private String propertyPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
