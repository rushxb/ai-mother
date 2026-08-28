package com.rush.rushaicodemother.orchestration.governance.audit;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 审计保留与有界清理策略。 */
@Data
@Component
@Validated
public class GenerationControlAuditProperties {

    public static final Duration RETENTION = Duration.ofDays(90);
    public static final String CLEANUP_INTERVAL = "PT1H";
    public static final int CLEANUP_BATCH_SIZE = 500;

    private Duration retention = RETENTION;

    @Min(1)
    @Max(5000)
    private int cleanupBatchSize = CLEANUP_BATCH_SIZE;

    @AssertTrue(message = "生成控制审计保留时间必须为正数")
    public boolean isRetentionPositive() {
        return retention != null && !retention.isZero() && !retention.isNegative();
    }
}
