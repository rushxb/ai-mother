package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 浏览器支持的基准分级器控件，适用于专门的发布验证工作人员。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.browser-grading")
public class GenerationBenchmarkBrowserProperties {

    /** 页面稳定等待时长，属于固定评分口径。 */
    public static final Duration SETTLE_DELAY = Duration.ofSeconds(2);

    private boolean enabled;

    private Duration settleDelay = SETTLE_DELAY;

    @AssertTrue(message = "浏览器评分的页面稳定等待时长必须在 0 到 30 秒之间")
    public boolean isSettleDelayValid() {
        return settleDelay != null
                && !settleDelay.isNegative()
                && settleDelay.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
