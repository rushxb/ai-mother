package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * 项目校验与构建命令的固定超时和输出资源上限。
 */
@Data
@Component
@Validated
public class ProjectCommandProperties {

    public static final Duration TOOL_SCRIPT_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration LIGHT_VALIDATION_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration LIGHT_BUILD_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration FULL_BUILD_TIMEOUT = Duration.ofMinutes(4);
    public static final Duration GO_TEST_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration GO_TEST_IDLE_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    public static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    public static final int MAX_OUTPUT_LENGTH = 12_000;
    public static final int RECENT_BUILD_RESULT_MAX_ENTRIES = 500;

    /** AI 工具执行 lint、test、type-check 等脚本的总超时。 */
    private Duration toolScriptTimeout = TOOL_SCRIPT_TIMEOUT;

    /** Vue 项目轻量校验脚本的总超时。 */
    private Duration lightValidationTimeout = LIGHT_VALIDATION_TIMEOUT;

    /** Vue 项目轻量构建脚本的总超时。 */
    private Duration lightBuildTimeout = LIGHT_BUILD_TIMEOUT;

    /** Vue 项目全量构建脚本的总超时。 */
    private Duration fullBuildTimeout = FULL_BUILD_TIMEOUT;

    /** Go 项目执行完整测试的总超时。 */
    private Duration goTestTimeout = GO_TEST_TIMEOUT;

    /** Go 编译或测试持续无输出时的超时。 */
    private Duration goTestIdleTimeout = GO_TEST_IDLE_TIMEOUT;

    /** 命令持续无输出的超时。 */
    private Duration idleTimeout = IDLE_TIMEOUT;

    /** 长时间运行命令的心跳日志间隔。 */
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    /** 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = OUTPUT_DRAIN_TIMEOUT;

    /** 内存中保留的命令输出最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = MAX_OUTPUT_LENGTH;

    /** 内存中最多保留的最近项目构建结果数量。 */
    @Min(10)
    @Max(10_000)
    private int recentBuildResultMaxEntries = RECENT_BUILD_RESULT_MAX_ENTRIES;

    /**
 * 校验各时长配置及其相互约束是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "项目命令相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(
                        toolScriptTimeout,
                        lightValidationTimeout,
                        lightBuildTimeout,
                        fullBuildTimeout,
                        goTestTimeout,
                        goTestIdleTimeout,
                        idleTimeout,
                        heartbeatInterval,
                        outputDrainTimeout
                )
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    /**
 * 判断心跳间隔是否安全。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "项目命令心跳间隔必须小于所有命令总超时")
    public boolean isHeartbeatIntervalSafe() {
        if (heartbeatInterval == null) {
            return false;
        }
        return Stream.of(toolScriptTimeout, lightValidationTimeout, lightBuildTimeout,
                        fullBuildTimeout, goTestTimeout, goTestIdleTimeout)
                .allMatch(timeout -> timeout != null && heartbeatInterval.compareTo(timeout) < 0);
    }
}
