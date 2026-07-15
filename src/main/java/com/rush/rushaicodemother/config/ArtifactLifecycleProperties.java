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

    /** robocopy 运行期间的心跳日志间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(30);

    /** robocopy 结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = Duration.ofSeconds(5);

    /** robocopy 单个输出流在内存中保留的最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = 8_000;

    /** Maximum number of files accepted in one artifact copy. */
    @Min(1)
    @Max(1_000_000)
    private int maxFiles = 20_000;

    /** Maximum number of directories, excluding the source root. */
    @Min(1)
    @Max(100_000)
    private int maxDirectories = 5_000;

    /** Maximum directory depth relative to the source root. */
    @Min(1)
    @Max(256)
    private int maxDirectoryDepth = 64;

    /** Maximum size of one artifact file in bytes. */
    @Min(1_024L)
    private long maxFileBytes = 100L * 1024 * 1024;

    /** Maximum cumulative file size for one artifact copy in bytes. */
    @Min(1_048_576L)
    private long maxTotalBytes = 2L * 1024 * 1024 * 1024;

    /** Maximum attempts for publishing or switching an artifact directory. */
    @Min(1)
    @Max(20)
    private int publishMaxAttempts = 5;

    /** Delay between retries after a transient directory access denial. */
    @Min(0)
    @Max(5_000)
    private long publishRetryDelayMillis = 50;

    @AssertTrue(message = "产物复制命令相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(copyTimeout, heartbeatInterval, outputDrainTimeout)
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
}
