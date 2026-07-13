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

/**
 * 项目校验与构建命令的超时和输出资源上限。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.project-command")
public class ProjectCommandProperties {

    /** AI 工具执行 lint、test、type-check 等脚本的总超时。 */
    private Duration toolScriptTimeout = Duration.ofMinutes(5);

    /** Vue 项目轻量校验脚本的总超时。 */
    private Duration lightValidationTimeout = Duration.ofSeconds(90);

    /** Vue 项目轻量构建脚本的总超时。 */
    private Duration lightBuildTimeout = Duration.ofMinutes(3);

    /** Vue 项目全量构建脚本的总超时。 */
    private Duration fullBuildTimeout = Duration.ofMinutes(4);

    /** 命令持续无输出的超时。 */
    private Duration idleTimeout = Duration.ofSeconds(90);

    /** 长时间运行命令的心跳日志间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /** 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = Duration.ofSeconds(2);

    /** 内存中保留的命令输出最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = 12_000;

    @AssertTrue(message = "项目命令相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(
                        toolScriptTimeout,
                        lightValidationTimeout,
                        lightBuildTimeout,
                        fullBuildTimeout,
                        idleTimeout,
                        heartbeatInterval,
                        outputDrainTimeout
                )
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    @AssertTrue(message = "项目命令心跳间隔必须小于所有命令总超时")
    public boolean isHeartbeatIntervalSafe() {
        if (heartbeatInterval == null) {
            return false;
        }
        return Stream.of(toolScriptTimeout, lightValidationTimeout, lightBuildTimeout, fullBuildTimeout)
                .allMatch(timeout -> timeout != null && heartbeatInterval.compareTo(timeout) < 0);
    }
}
