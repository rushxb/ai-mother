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

/** 本地 Git 生成提交的进程资源边界。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-commit")
public class GenerationCommitProperties {

    /** 单条 Git 命令的总超时。 */
    private Duration commandTimeout = Duration.ofSeconds(10);

    /** Git 命令运行期间的心跳日志间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(2);

    /** Git 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = Duration.ofSeconds(2);

    /** 单个输出流在内存中保留的最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = 8_000;

    /** 本地 Git 仓库提交锁条带数。 */
    @Min(1)
    @Max(1024)
    private int lockStripes = 64;

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
