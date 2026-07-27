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

    private boolean enabled;

    private Duration settleDelay = Duration.ofSeconds(2);

    @AssertTrue(message = "generation benchmark browser settle delay must be between zero and 30 seconds")
    public boolean isSettleDelayValid() {
        return settleDelay != null
                && !settleDelay.isNegative()
                && settleDelay.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
