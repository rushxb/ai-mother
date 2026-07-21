package com.rush.rushaicodemother.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Fails fast on dedicated browser-grading workers with incomplete browser automation assets. */
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

    @PostConstruct
    public void verify() {
        if (!benchmarkProperties.isEnabled()) {
            return;
        }
        screenshotProperties.requireChromeDriverPath();
        screenshotProperties.resolveChromeBinaryPath();
    }
}
