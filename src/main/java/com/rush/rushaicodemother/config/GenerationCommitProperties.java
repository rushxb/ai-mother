package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.stream.Stream;

/** 本地 Git 生成提交的固定进程资源边界。 */
@Data
@Component
@Validated
public class GenerationCommitProperties {

    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(2);
    public static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    public static final int MAX_OUTPUT_LENGTH = 8_000;
    public static final int LOCK_STRIPES = 64;
    public static final int MAX_FILES_PER_COMMIT = 20_000;
    public static final int MAX_PATHSPEC_BYTES = 2 * 1024 * 1024;

    /** 单条 Git 命令的总超时。 */
    private Duration commandTimeout = COMMAND_TIMEOUT;

    /** Git 命令运行期间的心跳日志间隔。 */
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    /** Git 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = OUTPUT_DRAIN_TIMEOUT;

    /** 单个输出流在内存中保留的最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = MAX_OUTPUT_LENGTH;

    /** 本地 Git 仓库提交锁条带数。 */
    @Min(1)
    @Max(1024)
    private int lockStripes = LOCK_STRIPES;

    /** 单次生成提交允许处理的最大去重文件数。 */
    @Min(1)
    @Max(100_000)
    private int maxFilesPerCommit = MAX_FILES_PER_COMMIT;

    /** NUL 分隔 Git pathspec 文件允许占用的最大 UTF-8 字节数。 */
    @Min(4096)
    @Max(16 * 1024 * 1024)
    private int maxPathspecBytes = MAX_PATHSPEC_BYTES;

    @AssertTrue(message = "Git 提交相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(commandTimeout, heartbeatInterval, outputDrainTimeout)
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    @AssertTrue(message = "Git 命令心跳间隔必须小于命令总超时")
    public boolean isHeartbeatIntervalSafe() {
        return commandTimeout != null
                && heartbeatInterval != null
                && heartbeatInterval.compareTo(commandTimeout) < 0;
    }
}
