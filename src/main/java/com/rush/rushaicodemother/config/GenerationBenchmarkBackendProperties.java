package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/** 生成基准测试的后端运行时评分配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.backend-grading")
public class GenerationBenchmarkBackendProperties {

    /** 被评分后端进程的启动等待上限。 */
    public static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);

    /** 单次探测请求超时。 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    /** 就绪状态轮询间隔。 */
    public static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** 被评分进程的总存活上限。 */
    public static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

    /** 进程存活心跳间隔。 */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    /** 进程输出收尾读取超时。 */
    public static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(2);

    /** 进程优雅停止超时。 */
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    /** 保留的进程输出最大字符数。 */
    public static final int MAX_OUTPUT_LENGTH = 64 * 1024;

    /** 单次探测响应的最大字节数。 */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private boolean enabled;
    private Duration startupTimeout = STARTUP_TIMEOUT;
    private Duration requestTimeout = REQUEST_TIMEOUT;
    private Duration pollInterval = POLL_INTERVAL;
    private Duration processTimeout = PROCESS_TIMEOUT;
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;
    private Duration outputDrainTimeout = OUTPUT_DRAIN_TIMEOUT;
    private Duration shutdownTimeout = SHUTDOWN_TIMEOUT;
    private int maxOutputLength = MAX_OUTPUT_LENGTH;
    private int maxResponseBytes = MAX_RESPONSE_BYTES;
    private int portRangeStart = 19_000;
    private int portRangeEnd = 19_999;
    private Path workspaceRoot = Path.of(
            System.getProperty("java.io.tmpdir"),
            "ai-code-mother",
            "benchmark-backend-runtime"
    ).toAbsolutePath().normalize();

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "生成基准测试后端运行时评分配置无效")
    public boolean isConfigurationValid() {
        return positive(startupTimeout)
                && startupTimeout.compareTo(Duration.ofMinutes(2)) <= 0
                && positive(requestTimeout)
                && requestTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && positive(pollInterval)
                && pollInterval.compareTo(Duration.ofSeconds(5)) <= 0
                && positive(processTimeout)
                && processTimeout.compareTo(Duration.ofMinutes(10)) <= 0
                && processTimeout.compareTo(startupTimeout) > 0
                && positive(heartbeatInterval)
                && heartbeatInterval.compareTo(processTimeout) < 0
                && positive(outputDrainTimeout)
                && positive(shutdownTimeout)
                && maxOutputLength >= 1_024
                && maxOutputLength <= 4 * 1024 * 1024
                && maxResponseBytes >= 1_024
                && maxResponseBytes <= 1024 * 1024
                && portRangeStart >= 1
                && portRangeEnd <= 65_535
                && portRangeStart <= portRangeEnd
                && workspaceRoot != null;
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
