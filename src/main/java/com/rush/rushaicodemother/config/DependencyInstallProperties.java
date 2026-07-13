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
 * 项目依赖安装的重试、超时和资源上限配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.dependency-install")
public class DependencyInstallProperties {

    /** 单次 pnpm install 的总超时。 */
    private Duration commandTimeout = Duration.ofMinutes(5);

    /** 安装进程持续无输出的超时。 */
    private Duration idleTimeout = Duration.ofSeconds(90);

    /** 安装过程心跳日志间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /** Vite 运行时可加载性校验超时。 */
    private Duration runtimeValidationTimeout = Duration.ofSeconds(30);

    /** 进程结束后等待输出消费线程收口的时长。 */
    private Duration outputDrainTimeout = Duration.ofSeconds(2);

    /** 单个项目最多执行的安装次数。 */
    @Min(1)
    @Max(5)
    private int maxAttempts = 3;

    /** 内存中保留的安装输出最大字符数。 */
    @Min(1024)
    @Max(1_000_000)
    private int maxOutputLength = 12_000;

    /** 项目级本地安装锁条带数。 */
    @Min(1)
    @Max(1024)
    private int lockStripes = 64;

    @AssertTrue(message = "依赖安装相关超时必须全部大于 0")
    public boolean isDurationConfigurationValid() {
        return Stream.of(
                        commandTimeout,
                        idleTimeout,
                        heartbeatInterval,
                        runtimeValidationTimeout,
                        outputDrainTimeout
                )
                .allMatch(duration -> duration != null && !duration.isZero() && !duration.isNegative());
    }

    @AssertTrue(message = "依赖安装心跳间隔必须小于总超时")
    public boolean isHeartbeatIntervalSafe() {
        return commandTimeout != null
                && heartbeatInterval != null
                && heartbeatInterval.compareTo(commandTimeout) < 0;
    }
}
