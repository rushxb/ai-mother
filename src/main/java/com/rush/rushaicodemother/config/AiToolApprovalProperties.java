package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * AI 工具审批的固定配置属性。
 */
@Data
@Component
@Validated
public class AiToolApprovalProperties {

    public static final Duration TTL = Duration.ofMinutes(10);
    public static final Duration EXPIRATION_SCAN_INTERVAL = Duration.ofMinutes(1);
    public static final int EXPIRATION_BATCH_SIZE = 100;

    /** 审批记录有效期。 */
    private Duration ttl = TTL;
    /** 过期审批扫描周期。 */
    private Duration expirationScanInterval = EXPIRATION_SCAN_INTERVAL;
    /** 单次过期扫描处理上限。 */
    @Min(1)
    @Max(1000)
    private int expirationBatchSize = EXPIRATION_BATCH_SIZE;
    /** 最大执行尝试次数。 */
    @Min(1)
    @Max(10)
    private int maxExecutionAttempts = 3;

    @AssertTrue(message = "AI 工具审批有效期和过期扫描周期必须大于 0")
    public boolean isTtlPositive() {
        return positive(ttl) && positive(expirationScanInterval);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
