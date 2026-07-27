package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.stream.Stream;

/** 本地产物复制、发布和切换操作的资源边界。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.artifact-lifecycle")
public class ArtifactLifecycleProperties {

    /** 单次 robocopy 复制的总超时。 */
    private Duration copyTimeout = Duration.ofMinutes(15);

    /** 播种一个独立执行工作区的最大挂钟时间。 */
    private Duration executionWorkspaceCopyTimeout = Duration.ofMinutes(2);

    /** robocopy 运行期间的心跳日志间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(30);

    /** robocopy 结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = Duration.ofSeconds(5);

    /** robocopy 单个输出流在内存中保留的最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = 8_000;

    /** 一份工件副本中接受的最大文件数。 */
    @Min(1)
    @Max(1_000_000)
    private int maxFiles = 20_000;

    /** 最大目录数，不包括源根目录。 */
    @Min(1)
    @Max(100_000)
    private int maxDirectories = 5_000;

    /** 相对于源根目录的最大目录深度。 */
    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = 64;

    /** 一个工件文件的最大大小（以字节为单位）。 */
    @Min(1_024L)
    private long maxFileBytes = 100L * 1024 * 1024;

    /** 一份工件副本的最大累积文件大小（以字节为单位）。 */
    @Min(1_048_576L)
    private long maxTotalBytes = 2L * 1024 * 1024 * 1024;

    /** 发布或切换工件目录的最大尝试次数。 */
    @Min(1)
    @Max(20)
    private int publishMaxAttempts = 5;

    /** 短暂目录访问拒绝后重试之间的延迟。 */
    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = 50;

    /** 等待每个应用程序发布锁的最长时间。 */
    private Duration publicationLockTimeout = Duration.ofSeconds(30);

    /** 持久发布协调扫描之间的间隔。 */
    private Duration publicationReconciliationScanInterval = Duration.ofSeconds(30);

    /** 另一个节点可以回收同一发布协调项之前的延迟。 */
    private Duration publicationReconciliationRetryDelay = Duration.ofSeconds(30);

    @Min(1)
    @Max(1000)
    private int publicationReconciliationBatchSize = 100;

    @Min(1)
    @Max(100)
    private int publicationReconciliationMaxAttempts = 20;

    @AssertTrue(message = "产物复制命令相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(copyTimeout, executionWorkspaceCopyTimeout, heartbeatInterval, outputDrainTimeout)
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    @AssertTrue(message = "产物复制命令心跳间隔必须小于复制总超时")
    public boolean isHeartbeatIntervalSafe() {
        return copyTimeout != null
                && heartbeatInterval != null
                && heartbeatInterval.compareTo(copyTimeout) < 0;
    }

    @AssertTrue(message = "artifact maxTotalBytes must be greater than or equal to maxFileBytes")
    public boolean isCopySizeLimitValid() {
        return maxTotalBytes >= maxFileBytes;
    }

    @AssertTrue(message = "publication lock timeout must be positive")
    public boolean isPublicationLockTimeoutValid() {
        return publicationLockTimeout != null
                && !publicationLockTimeout.isZero()
                && !publicationLockTimeout.isNegative()
                && publicationReconciliationScanInterval != null
                && !publicationReconciliationScanInterval.isZero()
                && !publicationReconciliationScanInterval.isNegative()
                && publicationReconciliationRetryDelay != null
                && !publicationReconciliationRetryDelay.isZero()
                && !publicationReconciliationRetryDelay.isNegative();
    }
}
