package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * AI 工具审批配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-tool-approval")
public class AiToolApprovalProperties {
    /** 审批记录有效期。 */
    private Duration ttl = Duration.ofMinutes(10);
    /** 过期审批扫描周期。 */
    private Duration expirationScanInterval = Duration.ofMinutes(1);
    /** 单次过期扫描处理上限。 */
    @Min(1)
    @Max(1000)
    private int expirationBatchSize = 100;
    /** 最大执行尝试次数。 */
    @Min(1)
    @Max(10)
    private int maxExecutionAttempts = 3;

    @AssertTrue(message = "AI tool approval TTL must be positive")
    public boolean isTtlPositive() {
        return positive(ttl) && positive(expirationScanInterval);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
