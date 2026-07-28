package com.rush.rushaicodemother.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 如果浏览器自动化资产不完整，专门的浏览器分级工作人员会很快失败。 */
@Component
public class GenerationBenchmarkBrowserReadinessVerifier {

    private final GenerationBenchmarkBrowserProperties benchmarkProperties;
    private final ScreenshotProperties screenshotProperties;

    public GenerationBenchmarkBrowserReadinessVerifier(
            GenerationBenchmarkBrowserProperties benchmarkProperties,
            ScreenshotProperties screenshotProperties
    ) {
        this.benchmarkProperties = benchmarkProperties;
        this.screenshotProperties = screenshotProperties;
    }

    /** 验证生成基准测试浏览器就绪状态是否符合预期。 */
    @PostConstruct
    public void verify() {
        if (!benchmarkProperties.isEnabled()) {
            return;
        }
        screenshotProperties.requireChromeDriverPath();
        screenshotProperties.resolveChromeBinaryPath();
    }
}
