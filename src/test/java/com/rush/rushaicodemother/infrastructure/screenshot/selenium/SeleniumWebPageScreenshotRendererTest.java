package com.rush.rushaicodemother.infrastructure.screenshot.selenium;

import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SeleniumWebPageScreenshotRendererTest {

    private Path tempDirectory;
    private Path workspace;
    private SeleniumChromeDriverFactory driverFactory;
    private WebDriver driver;
    private SeleniumWebPageScreenshotRenderer renderer;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Path.of("target", "test-workspaces", "selenium-screenshot-renderer")
                .toAbsolutePath()
                .normalize();
        deleteRecursively(tempDirectory);
        workspace = Files.createDirectories(tempDirectory.resolve("workspace"));
        driverFactory = mock(SeleniumChromeDriverFactory.class);
        driver = mock(
                WebDriver.class,
                withSettings().extraInterfaces(TakesScreenshot.class, JavascriptExecutor.class)
        );
        ScreenshotProperties properties = new ScreenshotProperties();
        properties.setReadyStateTimeout(Duration.ofSeconds(1));
        properties.setPostLoadDelay(Duration.ZERO);
        properties.setCompressionQuality(0.8F);
        renderer = new SeleniumWebPageScreenshotRenderer(driverFactory, properties);
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldCreateCompressedImageAndAlwaysCloseDriver() throws IOException {
        URI target = URI.create("http://localhost:91/static/App123/");
        when(driverFactory.createScreenshotDriver()).thenReturn(driver);
        when(((JavascriptExecutor) driver).executeScript("return document.readyState"))
                .thenReturn("complete");
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES))
                .thenReturn(createPngBytes());

        Path screenshot = renderer.render(target, workspace);

        assertTrue(Files.isRegularFile(screenshot));
        assertTrue(screenshot.startsWith(workspace));
        assertFalse(Files.exists(workspace.resolve("source.png")));
        verify(driver).get(target.toASCIIString());
        verify(driver).quit();
    }

    @Test
    void shouldCloseDriverWhenNavigationFails() {
        when(driverFactory.createScreenshotDriver()).thenReturn(driver);
        doThrow(new IllegalStateException("navigation failed"))
                .when(driver).get("http://localhost:91/static/App123/");

        assertThrows(
                BusinessException.class,
                () -> renderer.render(
                        URI.create("http://localhost:91/static/App123/"),
                        workspace
                )
        );

        verify(driver).quit();
    }

    private byte[] createPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
