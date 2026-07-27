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

    private boolean enabled = true;

    /** 仅对显式声明可安全重放的节点省略执行前检查点，默认关闭并通过基准后灰度。 */
    private boolean replaySafeStartCheckpointElisionEnabled = false;

    /** 合并连续可重放节点的完成检查点，默认关闭并限制单次合并跨度。 */
    private boolean replaySafeCompletionCheckpointCoalescingEnabled = false;

    /** 每完成多少个连续可重放节点强制建立一次持久化边界。 */
    @Min(2)
    @Max(64)
    private int replaySafeCompletionCheckpointInterval = 4;

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

    @AssertTrue(message = "可重放节点完成检查点合并必须同时启用执行前检查点省略")
    public boolean isReplaySafeCheckpointOptimizationValid() {
        return !replaySafeCompletionCheckpointCoalescingEnabled
                || replaySafeStartCheckpointElisionEnabled;
    }
}
