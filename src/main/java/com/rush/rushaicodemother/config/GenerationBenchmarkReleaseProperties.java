package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.release-gate")
public class GenerationBenchmarkReleaseProperties {

    @Min(1)
    private int minimumTaskCount = 12;

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
    private double minimumFunctionalEvaluationRate = 0.50;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumFunctionalPassRate = 0.90;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumDiffScopeEvaluationRate = 0.50;

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
    private double minimumRuntimeEvaluationRate = 0.75;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRuntimePassRate = 0.90;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumVisualEvaluationRate = 0.75;

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

    @Min(1)
    private long maximumAverageTokens = 250_000L;

    @Min(1)
    private long maximumAverageCreditCost = 10L;

    @AssertTrue(message = "generation benchmark duration limits must be positive")
    public boolean isDurationConfigurationValid() {
        return positive(maximumP90Duration)
                && positive(maximumP99Duration)
                && positive(maximumP90FirstTokenLatency);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
