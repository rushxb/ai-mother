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

    /** 证据最长信任期限，属于固定发布契约。 */
    public static final Duration MAXIMUM_VALIDITY = Duration.ofDays(7);

    /** 单份证据报告的最大字节数。 */
    public static final int MAXIMUM_REPORT_BYTES = 5 * 1024 * 1024;

    /** 必须由秘密管理者在摄取或验证证据的环境中提供。 */
    private String signingSecret = "";

    private String graderFingerprint = "generation-benchmark-graders-v6";

    private Duration maximumValidity = MAXIMUM_VALIDITY;

    @Min(1024)
    @Max(10 * 1024 * 1024)
    private int maximumReportBytes = MAXIMUM_REPORT_BYTES;

    @AssertTrue(message = "生成评测证据配置无效")
    public boolean isConfigurationValid() {
        return graderFingerprint != null && !graderFingerprint.isBlank()
                && graderFingerprint.length() <= 128
                && maximumValidity != null
                && !maximumValidity.isZero()
                && !maximumValidity.isNegative();
    }
}
