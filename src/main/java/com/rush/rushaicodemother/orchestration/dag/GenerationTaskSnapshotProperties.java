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
 * 诊断编排任务快照的有限本地持久性设置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-snapshot")
public class GenerationTaskSnapshotProperties {

    private static final int MIN_SNAPSHOT_BYTES = 16 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;

    public static final int REPLAY_SAFE_COMPLETION_CHECKPOINT_INTERVAL = 4;
    /** 单快照字节上限默认值，避免与 {@link #MAX_SNAPSHOT_BYTES} 校验边界常量重名。 */
    public static final int DEFAULT_MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SNAPSHOTS_PER_APP = 100;
    public static final Duration RETENTION = Duration.ofDays(7);
    public static final int LOCK_STRIPES = 64;

    private boolean enabled = true;

    /** 仅对显式声明可安全重放的节点省略执行前检查点，默认关闭并通过基准后灰度。 */
    private boolean replaySafeStartCheckpointElisionEnabled = false;

    /** 合并连续可重放节点的完成检查点，默认关闭并限制单次合并跨度。 */
    private boolean replaySafeCompletionCheckpointCoalescingEnabled = false;

    /** 每完成多少个连续可重放节点强制建立一次持久化边界。 */
    @Min(2)
    @Max(64)
    private int replaySafeCompletionCheckpointInterval = REPLAY_SAFE_COMPLETION_CHECKPOINT_INTERVAL;

    @NotNull
    private Path rootDirectory = Path.of(AppConstant.ORCHESTRATION_TASK_ROOT_DIR);

    @Min(MIN_SNAPSHOT_BYTES)
    @Max(MAX_SNAPSHOT_BYTES)
    private int maxSnapshotBytes = DEFAULT_MAX_SNAPSHOT_BYTES;

    @Min(1)
    @Max(1_000)
    private int maxSnapshotsPerApp = MAX_SNAPSHOTS_PER_APP;

    @NotNull
    private Duration retention = RETENTION;

    @Min(1)
    @Max(1_024)
    private int lockStripes = LOCK_STRIPES;

    @AssertTrue(message = "编排任务快照目录和保留期限配置无效")
    public boolean isStorageConfigurationValid() {
        return rootDirectory != null
                && !rootDirectory.toString().isBlank()
                && retention != null
                && !retention.isZero()
                && !retention.isNegative();
    }

    @AssertTrue(message = "可重放节点完成检查点合并必须同时启用执行前检查点省略")
    public boolean isReplaySafeCheckpointOptimizationValid() {
        return !replaySafeCompletionCheckpointCoalescingEnabled
                || replaySafeStartCheckpointElisionEnabled;
    }
}
