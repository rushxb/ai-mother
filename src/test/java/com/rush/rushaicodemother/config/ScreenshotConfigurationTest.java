package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.infrastructure.screenshot.selenium.SeleniumChromeDriverFactory;
import com.rush.rushaicodemother.service.screenshot.DefaultScreenshotService;
import com.rush.rushaicodemother.service.screenshot.DisabledScreenshotService;
import com.rush.rushaicodemother.service.screenshot.ScreenshotService;
import com.rush.rushaicodemother.service.screenshot.WebPageScreenshotRenderer;
import com.rush.rushaicodemother.service.storage.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ScreenshotConfigurationTest {

    private Path tempDirectory;
    private Path driverPath;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Path.of("target", "test-workspaces", "screenshot-configuration")
                .toAbsolutePath()
                .normalize();
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);
        driverPath = Files.writeString(tempDirectory.resolve("chromedriver.exe"), "driver");
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldCreateDisabledServiceWithoutObjectStorageDependency() {
        contextRunner()
                .withPropertyValues(
                        "app.screenshot.enabled=false",
                        "app.screenshot.max-concurrency=3",
                        "app.screenshot.queue-capacity=5"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ScreenshotService.class);
                    assertThat(context).getBean(ScreenshotService.class)
                            .isInstanceOf(DisabledScreenshotService.class);
                    assertThat(context).doesNotHaveBean(WebPageScreenshotRenderer.class);
                    assertThat(context).hasSingleBean(SeleniumChromeDriverFactory.class);
                    ThreadPoolTaskExecutor executor = context.getBean(
                            ScreenshotConfiguration.SCREENSHOT_TASK_EXECUTOR,
                            ThreadPoolTaskExecutor.class
                    );
                    assertEquals(3, executor.getCorePoolSize());
                    assertEquals(3, executor.getMaxPoolSize());
                    assertEquals(5, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
                });
    }

    @Test
    void shouldCreateRendererAndDefaultServiceWhenEnabled() {
        contextRunner()
                .withBean(ObjectStorageService.class, () -> mock(ObjectStorageService.class))
                .withPropertyValues(
                        "app.screenshot.enabled=true",
                        "app.screenshot.chrome-driver-path=" + propertyPath(driverPath)
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ScreenshotService.class);
                    assertThat(context).getBean(ScreenshotService.class)
                            .isInstanceOf(DefaultScreenshotService.class);
                    assertThat(context).hasSingleBean(WebPageScreenshotRenderer.class);
                });
    }

    private ApplicationContextRunner contextRunner() {
        CodeDeploymentProperties deploymentProperties = new CodeDeploymentProperties();
        deploymentProperties.setDeployHost("http://localhost:91");
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ScreenshotConfiguration.class)
                .withBean(CodeDeploymentProperties.class, () -> deploymentProperties);
    }

    private String propertyPath(Path path) {
        return path.toString().replace('\\', '/');
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
