package com.rush.rushaicodemother.orchestration.dag;

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
 * Bounded local persistence settings for diagnostic orchestration task snapshots.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-snapshot")
public class GenerationTaskSnapshotProperties {

    private static final int MIN_SNAPSHOT_BYTES = 16 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;

    private boolean enabled = true;

    @NotNull
    private Path rootDirectory = Path.of(AppConstant.ORCHESTRATION_TASK_ROOT_DIR);

    @Min(MIN_SNAPSHOT_BYTES)
    @Max(MAX_SNAPSHOT_BYTES)
    private int maxSnapshotBytes = 2 * 1024 * 1024;

    @Min(1)
    @Max(1_000)
    private int maxSnapshotsPerApp = 100;

    @NotNull
    private Duration retention = Duration.ofDays(7);

    @Min(1)
    @Max(1_024)
    private int lockStripes = 64;

    @AssertTrue(message = "编排任务快照目录和保留期限配置无效")
    public boolean isStorageConfigurationValid() {
        return rootDirectory != null
                && !rootDirectory.toString().isBlank()
                && retention != null
                && !retention.isZero()
                && !retention.isNegative();
    }
}
