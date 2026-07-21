package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationBenchmarkBrowserReadinessVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledWorkerMustNotRequireBrowserAssets() {
        GenerationBenchmarkBrowserProperties benchmark =
                new GenerationBenchmarkBrowserProperties();
        GenerationBenchmarkBrowserReadinessVerifier verifier =
                new GenerationBenchmarkBrowserReadinessVerifier(
                        benchmark,
                        new ScreenshotProperties()
                );

        assertDoesNotThrow(verifier::verify);
    }

    @Test
    void enabledWorkerMustFailFastWithoutDriverAndAcceptPinnedFile() throws Exception {
        GenerationBenchmarkBrowserProperties benchmark =
                new GenerationBenchmarkBrowserProperties();
        benchmark.setEnabled(true);
        ScreenshotProperties screenshot = new ScreenshotProperties();
        GenerationBenchmarkBrowserReadinessVerifier verifier =
                new GenerationBenchmarkBrowserReadinessVerifier(benchmark, screenshot);

        assertThrows(RuntimeException.class, verifier::verify);

        Path driver = Files.writeString(temporaryDirectory.resolve("chromedriver"), "driver");
        screenshot.setChromeDriverPath(driver.toString());
        assertDoesNotThrow(verifier::verify);
    }
}
