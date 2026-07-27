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

    private boolean enabled;
    private Duration startupTimeout = Duration.ofSeconds(45);
    private Duration requestTimeout = Duration.ofSeconds(3);
    private Duration pollInterval = Duration.ofMillis(100);
    private Duration processTimeout = Duration.ofMinutes(2);
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration outputDrainTimeout = Duration.ofSeconds(2);
    private Duration shutdownTimeout = Duration.ofSeconds(10);
    private int maxOutputLength = 64 * 1024;
    private int maxResponseBytes = 64 * 1024;
    private int portRangeStart = 19_000;
    private int portRangeEnd = 19_999;
    private Path workspaceRoot = Path.of(
            System.getProperty("java.io.tmpdir"),
            "ai-code-mother",
            "benchmark-backend-runtime"
    ).toAbsolutePath().normalize();

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
