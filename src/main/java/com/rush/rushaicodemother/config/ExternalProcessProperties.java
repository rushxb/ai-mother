package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 外部进程树的统一生命周期配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.external-process")
public class ExternalProcessProperties {

    private static final Duration MAX_TERMINATION_GRACE_PERIOD = Duration.ofMinutes(5);

    /** 发送正常终止信号后，等待进程树自行退出的宽限期。 */
    private Duration terminationGracePeriod = Duration.ofSeconds(2);

    @AssertTrue(message = "外部进程终止宽限期必须大于 0 且不超过 5 分钟")
    public boolean isTerminationGracePeriodValid() {
        return terminationGracePeriod != null
                && !terminationGracePeriod.isZero()
                && !terminationGracePeriod.isNegative()
                && terminationGracePeriod.compareTo(MAX_TERMINATION_GRACE_PERIOD) <= 0;
    }
}