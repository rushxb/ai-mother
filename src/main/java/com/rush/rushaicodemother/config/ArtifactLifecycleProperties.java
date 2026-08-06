package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.stream.Stream;

/** 本地产物复制、发布和切换操作的固定资源边界。 */
@Data
@Component
@Validated
public class ArtifactLifecycleProperties {

    public static final Duration COPY_TIMEOUT = Duration.ofMinutes(15);
    public static final Duration EXECUTION_WORKSPACE_COPY_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    public static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    public static final int MAX_OUTPUT_LENGTH = 8_000;
    public static final int MAX_FILES = 20_000;
    public static final int MAX_DIRECTORIES = 5_000;
    public static final int MAX_DIRECTORY_DEPTH = 64;
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int PUBLISH_MAX_ATTEMPTS = 5;
    public static final long PUBLISH_RETRY_DELAY_MILLIS = 50;
    public static final Duration PUBLICATION_LOCK_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration PUBLICATION_RECONCILIATION_SCAN_INTERVAL = Duration.ofSeconds(30);
    public static final Duration PUBLICATION_RECONCILIATION_RETRY_DELAY = Duration.ofSeconds(30);
    public static final int PUBLICATION_RECONCILIATION_BATCH_SIZE = 100;
    public static final int PUBLICATION_RECONCILIATION_MAX_ATTEMPTS = 20;

    /** 单次 robocopy 复制的总超时。 */
    private Duration copyTimeout = COPY_TIMEOUT;

    /** 播种一个独立执行工作区的最大挂钟时间。 */
    private Duration executionWorkspaceCopyTimeout = EXECUTION_WORKSPACE_COPY_TIMEOUT;

    /** robocopy 运行期间的心跳日志间隔。 */
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    /** robocopy 结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = OUTPUT_DRAIN_TIMEOUT;

    /** robocopy 单个输出流在内存中保留的最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = MAX_OUTPUT_LENGTH;

    /** 一份工件副本中接受的最大文件数。 */
    @Min(1)
    @Max(1_000_000)
    private int maxFiles = MAX_FILES;

    /** 最大目录数，不包括源根目录。 */
    @Min(1)
    @Max(100_000)
    private int maxDirectories = MAX_DIRECTORIES;

    /** 相对于源根目录的最大目录深度。 */
    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = MAX_DIRECTORY_DEPTH;

    /** 一个工件文件的最大大小（以字节为单位）。 */
    @Min(1_024L)
    private long maxFileBytes = MAX_FILE_BYTES;

    /** 一份工件副本的最大累积文件大小（以字节为单位）。 */
    @Min(1_048_576L)
    private long maxTotalBytes = MAX_TOTAL_BYTES;

    /** 发布或切换工件目录的最大尝试次数。 */
    @Min(1)
    @Max(20)
    private int publishMaxAttempts = PUBLISH_MAX_ATTEMPTS;

    /** 短暂目录访问拒绝后重试之间的延迟。 */
    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = PUBLISH_RETRY_DELAY_MILLIS;

    /** 等待每个应用程序发布锁的最长时间。 */
    private Duration publicationLockTimeout = PUBLICATION_LOCK_TIMEOUT;

    /** 持久发布协调扫描之间的间隔。 */
    private Duration publicationReconciliationScanInterval = PUBLICATION_RECONCILIATION_SCAN_INTERVAL;

    /** 另一个节点可以回收同一发布协调项之前的延迟。 */
    private Duration publicationReconciliationRetryDelay = PUBLICATION_RECONCILIATION_RETRY_DELAY;

    @Min(1)
    @Max(1000)
    private int publicationReconciliationBatchSize = PUBLICATION_RECONCILIATION_BATCH_SIZE;

    @Min(1)
    @Max(100)
    private int publicationReconciliationMaxAttempts = PUBLICATION_RECONCILIATION_MAX_ATTEMPTS;

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

    @AssertTrue(message = "产物复制总字节上限必须大于等于单文件上限")
    public boolean isCopySizeLimitValid() {
        return maxTotalBytes >= maxFileBytes;
    }

    /**
 * 判断发布锁超时是否有效。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "发布锁超时及发布协调间隔必须全部大于 0")
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
