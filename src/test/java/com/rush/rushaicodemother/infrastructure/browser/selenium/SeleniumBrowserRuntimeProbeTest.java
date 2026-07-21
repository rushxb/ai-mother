package com.rush.rushaicodemother.infrastructure.browser.selenium;

import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.infrastructure.screenshot.selenium.SeleniumChromeDriverFactory;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.Logs;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SeleniumBrowserRuntimeProbeTest {

    @Test
    void probeMustCollectBoundedEvidenceAndAlwaysCloseDriver() throws Exception {
        SeleniumChromeDriverFactory driverFactory = mock(SeleniumChromeDriverFactory.class);
        WebDriver driver = mock(
                WebDriver.class,
                withSettings().extraInterfaces(TakesScreenshot.class, JavascriptExecutor.class)
        );
        WebDriver.Options options = mock(WebDriver.Options.class);
        Logs logs = mock(Logs.class);
        when(driverFactory.createIsolatedDiagnosticDriver()).thenReturn(driver);
        when(driver.getCurrentUrl()).thenReturn("http://127.0.0.1:5180/");
        when(((JavascriptExecutor) driver).executeScript(anyString())).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            if ("return document.readyState".equals(script)) {
                return "complete";
            }
            return """
                    {"title":"Dashboard","readyState":"complete","bodyTextLength":20,
                    "bodyChildCount":1,"appNodeExists":true,"appNodeChildCount":1,
                    "visibleElementCount":4,"documentWidth":800,"documentHeight":600,
                    "viteErrorOverlayPresent":false,"firstText":"Dashboard content",
                    "firstHeading":"Dashboard","scripts":[],"stylesheets":[]}
                    """;
        });
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES))
                .thenReturn(pngBytes(800, 600));
        when(driver.manage()).thenReturn(options);
        when(options.logs()).thenReturn(logs);
        when(logs.get(LogType.BROWSER)).thenReturn(new LogEntries(List.of()));
        ScreenshotProperties properties = new ScreenshotProperties();
        properties.setReadyStateTimeout(Duration.ofSeconds(1));
        SeleniumBrowserRuntimeProbe probe = new SeleniumBrowserRuntimeProbe(
                driverFactory,
                properties
        );

        BrowserRuntimeObservation observation = probe.inspect(
                URI.create("http://127.0.0.1:5180/"),
                Duration.ZERO
        );

        assertTrue(observation.appNodeExists());
        assertTrue(observation.screenshot().captured());
        verify(driver).quit();
    }

    @Test
    void navigationFailureMustStillCloseDriver() {
        SeleniumChromeDriverFactory driverFactory = mock(SeleniumChromeDriverFactory.class);
        WebDriver driver = mock(
                WebDriver.class,
                withSettings().extraInterfaces(TakesScreenshot.class, JavascriptExecutor.class)
        );
        when(driverFactory.createIsolatedDiagnosticDriver()).thenReturn(driver);
        doThrow(new IllegalStateException("navigation failed"))
                .when(driver).get("http://127.0.0.1:5180/");
        SeleniumBrowserRuntimeProbe probe = new SeleniumBrowserRuntimeProbe(
                driverFactory,
                new ScreenshotProperties()
        );

        assertThrows(IllegalStateException.class, () -> probe.inspect(
                URI.create("http://127.0.0.1:5180/"),
                Duration.ZERO
        ));

        verify(driver).quit();
    }

    @Test
    void uniformScreenshotMustBeRejectedByVisualStats() {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();

        BrowserRuntimeObservation.ScreenshotStats stats =
                SeleniumBrowserRuntimeProbe.analyzeImage(image);

        assertTrue(stats.captured());
        assertTrue(stats.nearUniform());
    }

    @Test
    void contrastingScreenshotMustProvideUsefulVisualEvidence() {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 400, 300);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(400, 300, 400, 300);
        graphics.dispose();

        BrowserRuntimeObservation.ScreenshotStats stats =
                SeleniumBrowserRuntimeProbe.analyzeImage(image);

        assertFalse(stats.nearUniform());
        assertTrue(stats.sampledColorBuckets() >= 3);
        assertTrue(stats.luminanceRange() > 100);
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width / 2, height / 2);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(width / 2, height / 2, width / 2, height / 2);
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
