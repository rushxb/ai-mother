package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private Path tempDirectory;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Path.of("target", "test-workspaces", "screenshot-properties")
                .toAbsolutePath()
                .normalize();
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldAllowMissingDriverWhenScreenshotIsDisabled() {
        assertTrue(validator.validate(new ScreenshotProperties()).isEmpty());
    }

    @Test
    void shouldRequireSafeDriverFileWhenScreenshotIsEnabled() throws IOException {
        ScreenshotProperties properties = new ScreenshotProperties();
        properties.setEnabled(true);
        assertFalse(validator.validate(properties).isEmpty());

        Path driver = Files.writeString(tempDirectory.resolve("chromedriver.exe"), "driver");
        properties.setChromeDriverPath(driver.toString());
        assertTrue(validator.validate(properties).isEmpty());

        properties.setChromeDriverPath(tempDirectory.resolve("missing.exe").toString());
        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnsafeRuntimeLimits() {
        ScreenshotProperties properties = new ScreenshotProperties();
        properties.setMaxConcurrency(0);
        assertFalse(validator.validate(properties).isEmpty());

        properties.setMaxConcurrency(2);
        properties.setCompressionQuality(1.1F);
        assertFalse(validator.validate(properties).isEmpty());

        properties.setCompressionQuality(0.3F);
        properties.setPostLoadDelay(Duration.ofSeconds(31));
        assertFalse(validator.validate(properties).isEmpty());
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
