package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 生成基准测试发布配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.release-gate")
public class GenerationBenchmarkReleaseProperties {

    @Min(1)
    private int minimumTaskCount = 32;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumSuccessRate = 0.95;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumBuildPassRate = 0.90;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumStructuralEvaluationRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumStructuralPassRate = 0.95;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumFunctionalEvaluationRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumFunctionalPassRate = 0.90;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumDiffScopeEvaluationRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumDiffScopePassRate = 0.95;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumSecurityEvaluationRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumSecurityPassRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRuntimeEvaluationRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRuntimePassRate = 0.90;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumVisualEvaluationRate = 1.0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumVisualPassRate = 0.90;

    private boolean requirePromptBundleId = true;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double maximumFallbackRate = 0.20;

    @NotNull
    private Duration maximumP90Duration = Duration.ofMinutes(5);

    @NotNull
    private Duration maximumP99Duration = Duration.ofMinutes(10);

    @NotNull
    private Duration maximumP90FirstTokenLatency = Duration.ofSeconds(15);

    @NotNull
    private Duration maximumP99FirstTokenLatency = Duration.ofSeconds(30);

    @DecimalMin("1.0")
    @DecimalMax("1.0")
    private double minimumFirstPreviewObservationRate = 1.0;

    @NotNull
    private Map<GenerationMode, Duration> maximumP90FirstPreviewLatencyByMode =
            defaultMaximumP90FirstPreviewLatencyByMode();

    @NotNull
    private Duration maximumP99FirstPreviewLatency = Duration.ofMinutes(5);

    @Min(1)
    private long maximumAverageTokens = 250_000L;

    @Min(1)
    private long maximumAverageCreditCost = 10L;

    @AssertTrue(message = "生成质量评测的耗时门禁配置无效")
    public boolean isDurationConfigurationValid() {
        if (!positive(maximumP90Duration)
                || !positive(maximumP99Duration)
                || !positive(maximumP90FirstTokenLatency)
                || !positive(maximumP99FirstTokenLatency)
                || !positive(maximumP99FirstPreviewLatency)
                || Double.compare(minimumFirstPreviewObservationRate, 1.0) != 0
                || maximumP90Duration.compareTo(maximumP99Duration) > 0
                || maximumP90FirstTokenLatency.compareTo(maximumP99FirstTokenLatency) > 0
                || maximumP90FirstPreviewLatencyByMode == null
                || maximumP90FirstPreviewLatencyByMode.size() != GenerationMode.values().length) {
            return false;
        }
        for (GenerationMode mode : GenerationMode.values()) {
            Duration maximum = maximumP90FirstPreviewLatencyByMode.get(mode);
            if (!positive(maximum) || maximum.compareTo(maximumP99FirstPreviewLatency) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static Map<GenerationMode, Duration> defaultMaximumP90FirstPreviewLatencyByMode() {
        EnumMap<GenerationMode, Duration> limits = new EnumMap<>(GenerationMode.class);
        limits.put(GenerationMode.CREATE, Duration.ofSeconds(60));
        limits.put(GenerationMode.LIGHT_EDIT, Duration.ofSeconds(90));
        limits.put(GenerationMode.AGENT_EDIT, Duration.ofMinutes(3));
        limits.put(GenerationMode.HEAVY_EXPERT, Duration.ofMinutes(5));
        return limits;
    }
}
