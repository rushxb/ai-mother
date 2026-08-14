package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 物理模型调用账本的恢复策略。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-invocation-ledger")
public class AiModelInvocationLedgerProperties {

    private static final Duration MINIMUM_RECOVERY_GRACE = Duration.ofSeconds(10);
    private static final Duration MAXIMUM_RECOVERY_GRACE = Duration.ofMinutes(30);

    /** 等待 provider 异步终态回调的宽限，超时后由 reconciler 恢复。 */
    private Duration recoveryGrace = Duration.ofMinutes(2);

    @AssertTrue(message = "AI model invocation ledger recovery grace must be between 10s and 30m")
    public boolean isRecoveryGraceValid() {
        return recoveryGrace != null
                && !recoveryGrace.minus(MINIMUM_RECOVERY_GRACE).isNegative()
                && !MAXIMUM_RECOVERY_GRACE.minus(recoveryGrace).isNegative();
    }
}
