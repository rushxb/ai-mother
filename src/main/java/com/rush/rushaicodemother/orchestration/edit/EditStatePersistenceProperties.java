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

    private boolean enabled = true;

    @NotNull
    private Path rootDirectory = Path.of(AppConstant.EDIT_STATE_ROOT_DIR);

    @Min(1)
    @Max(100_000)
    private long maxCacheEntries = 1_000;

    @NotNull
    private Duration cacheExpireAfterAccess = Duration.ofHours(2);

    @NotNull
    private Duration stateRetention = Duration.ofHours(24);

    @Min(1)
    @Max(100_000)
    private int maxPersistedApps = 10_000;

    @Min(MIN_STATE_FILE_BYTES)
    @Max(MAX_STATE_FILE_BYTES)
    private int maxStateFileBytes = 1024 * 1024;

    @Min(1)
    @Max(200)
    private int maxRecentEdits = 20;

    @Min(1)
    @Max(1_000)
    private int maxRecentFiles = 50;

    @Min(1)
    @Max(200)
    private int maxRecentValidations = 20;

    @Min(16)
    @Max(256)
    private int maxTaskIdLength = 128;

    @Min(128)
    @Max(4_096)
    private int maxFilePathLength = 1_024;

    @Min(1)
    @Max(1_024)
    private int lockStripes = 64;

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
