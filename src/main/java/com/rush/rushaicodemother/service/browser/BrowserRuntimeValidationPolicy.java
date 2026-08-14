package com.rush.rushaicodemother.service.browser;

import java.time.Duration;

/** 浏览器事实的验证口径；生产与 Benchmark 可共享实现而选择不同视觉要求。 */
public record BrowserRuntimeValidationPolicy(
        Duration settleDelay,
        boolean requireVisualEvidence
) {

    private static final Duration MAX_SETTLE_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SETTLE_DELAY = Duration.ofSeconds(2);

    public BrowserRuntimeValidationPolicy {
        settleDelay = settleDelay == null ? DEFAULT_SETTLE_DELAY : settleDelay;
        if (settleDelay.isNegative() || settleDelay.compareTo(MAX_SETTLE_DELAY) > 0) {
            throw new IllegalArgumentException("浏览器页面稳定等待时长必须在 0 到 30 秒之间");
        }
    }

    public static BrowserRuntimeValidationPolicy productionRuntime() {
        return new BrowserRuntimeValidationPolicy(DEFAULT_SETTLE_DELAY, false);
    }

    public static BrowserRuntimeValidationPolicy benchmark(Duration settleDelay) {
        return new BrowserRuntimeValidationPolicy(settleDelay, true);
    }
}
