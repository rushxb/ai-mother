package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Trust and retention policy for externally evaluated immutable benchmark evidence. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.evidence")
public class GenerationBenchmarkEvidenceProperties {

    /** Must be supplied by a secret manager in environments that ingest or verify evidence. */
    private String signingSecret = "";

    private String graderFingerprint = "generation-benchmark-graders-v1";

    private Duration maximumValidity = Duration.ofDays(7);

    @Min(1024)
    @Max(10 * 1024 * 1024)
    private int maximumReportBytes = 5 * 1024 * 1024;

    @AssertTrue(message = "generation benchmark evidence settings are invalid")
    public boolean isConfigurationValid() {
        return graderFingerprint != null && !graderFingerprint.isBlank()
                && graderFingerprint.length() <= 128
                && maximumValidity != null
                && !maximumValidity.isZero()
                && !maximumValidity.isNegative();
    }
}
