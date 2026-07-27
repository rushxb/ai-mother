package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 外部评估的不可变基准证据的信任和保留政策。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.evidence")
public class GenerationBenchmarkEvidenceProperties {

    /** 必须由秘密管理者在摄取或验证证据的环境中提供。 */
    private String signingSecret = "";

    private String graderFingerprint = "generation-benchmark-graders-v6";

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
