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
 * 项目依赖安装的固定重试、超时和资源上限。
 */
@Data
@Component
@Validated
public class DependencyInstallProperties {

    public static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    public static final Duration RUNTIME_VALIDATION_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    public static final int MAX_ATTEMPTS = 2;
    public static final int MAX_OUTPUT_LENGTH = 12_000;
    public static final int LOCK_STRIPES = 64;
    public static final Duration LOCK_POLICY_CHECK_INTERVAL = Duration.ofMillis(250);

    /** 单次 pnpm install 的总超时。 */
    private Duration commandTimeout = COMMAND_TIMEOUT;

    /** 安装进程持续无输出的超时。 */
    private Duration idleTimeout = IDLE_TIMEOUT;

    /** 安装过程心跳日志间隔。 */
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    /** Vite 运行时可加载性校验超时。 */
    private Duration runtimeValidationTimeout = RUNTIME_VALIDATION_TIMEOUT;

    /** 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = OUTPUT_DRAIN_TIMEOUT;

    /** 单个项目最多执行的安装次数。 */
    @Min(1)
    @Max(5)
    private int maxAttempts = MAX_ATTEMPTS;

    /** 内存中保留的安装输出最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = MAX_OUTPUT_LENGTH;

    /** 项目级本地安装锁条带数。 */
    @Min(1)
    @Max(1024)
    private int lockStripes = LOCK_STRIPES;

    /** 等待项目安装锁时检查任务取消和 Deadline 的间隔。 */
    private Duration lockPolicyCheckInterval = LOCK_POLICY_CHECK_INTERVAL;

    /**
 * 校验各时长配置及其相互约束是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "依赖安装相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(
                        commandTimeout,
                        idleTimeout,
                        heartbeatInterval,
                        runtimeValidationTimeout,
                        outputDrainTimeout,
                        lockPolicyCheckInterval
                )
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    @AssertTrue(message = "依赖安装心跳间隔必须小于命令和运行时校验总超时")
    public boolean isHeartbeatIntervalSafe() {
        return commandTimeout != null
                && runtimeValidationTimeout != null
                && heartbeatInterval != null
                && heartbeatInterval.compareTo(commandTimeout) < 0
                && heartbeatInterval.compareTo(runtimeValidationTimeout) < 0;
    }
}
