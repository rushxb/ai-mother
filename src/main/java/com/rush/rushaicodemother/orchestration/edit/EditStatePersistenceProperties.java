package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.constant.AppConstant;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 编辑状态本地持久化的容量、保留期限和并发边界。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.edit-state")
public class EditStatePersistenceProperties {

    private static final int MIN_STATE_FILE_BYTES = 16 * 1024;
    private static final int MAX_STATE_FILE_BYTES = 8 * 1024 * 1024;

    public static final long MAX_CACHE_ENTRIES = 1_000;
    public static final Duration CACHE_EXPIRE_AFTER_ACCESS = Duration.ofHours(2);
    public static final Duration STATE_RETENTION = Duration.ofHours(24);
    public static final int MAX_PERSISTED_APPS = 10_000;
    /** 单文件字节上限默认值，避免与 {@link #MAX_STATE_FILE_BYTES} 校验边界常量重名。 */
    public static final int DEFAULT_MAX_STATE_FILE_BYTES = 1024 * 1024;
    public static final int MAX_RECENT_EDITS = 20;
    public static final int MAX_RECENT_FILES = 50;
    public static final int MAX_RECENT_VALIDATIONS = 20;
    public static final int MAX_TASK_ID_LENGTH = 128;
    public static final int MAX_FILE_PATH_LENGTH = 1_024;
    public static final int LOCK_STRIPES = 64;

    private boolean enabled = true;

    @NotNull
    private Path rootDirectory = Path.of(AppConstant.EDIT_STATE_ROOT_DIR);

    @Min(1)
    @Max(100_000)
    private long maxCacheEntries = MAX_CACHE_ENTRIES;

    @NotNull
    private Duration cacheExpireAfterAccess = CACHE_EXPIRE_AFTER_ACCESS;

    @NotNull
    private Duration stateRetention = STATE_RETENTION;

    @Min(1)
    @Max(100_000)
    private int maxPersistedApps = MAX_PERSISTED_APPS;

    @Min(MIN_STATE_FILE_BYTES)
    @Max(MAX_STATE_FILE_BYTES)
    private int maxStateFileBytes = DEFAULT_MAX_STATE_FILE_BYTES;

    @Min(1)
    @Max(200)
    private int maxRecentEdits = MAX_RECENT_EDITS;

    @Min(1)
    @Max(1_000)
    private int maxRecentFiles = MAX_RECENT_FILES;

    @Min(1)
    @Max(200)
    private int maxRecentValidations = MAX_RECENT_VALIDATIONS;

    @Min(16)
    @Max(256)
    private int maxTaskIdLength = MAX_TASK_ID_LENGTH;

    @Min(128)
    @Max(4_096)
    private int maxFilePathLength = MAX_FILE_PATH_LENGTH;

    @Min(1)
    @Max(1_024)
    private int lockStripes = LOCK_STRIPES;

    @AssertTrue(message = "编辑状态持久化目录、缓存期限或保留期限配置无效")
    public boolean isStorageConfigurationValid() {
        return rootDirectory != null
                && !rootDirectory.toString().isBlank()
                && isPositive(cacheExpireAfterAccess)
                && isPositive(stateRetention)
                && cacheExpireAfterAccess.compareTo(stateRetention) <= 0;
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
