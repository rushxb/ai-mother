package com.rush.rushaicodemother.infrastructure.screenshot.selenium;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;

import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;

/**
 * 基于部署环境显式驱动路径创建独立 Chrome 会话。
 *
 * <p>每次调用都创建新会话，调用方必须在 {@code finally} 中执行 {@link WebDriver#quit()}。</p>
 */
@Slf4j
public final class SeleniumChromeDriverFactory {

    private final ScreenshotProperties properties;

    public SeleniumChromeDriverFactory(ScreenshotProperties properties) {
        this.properties = properties;
    }

    public WebDriver createScreenshotDriver() {
        return createDriver(false);
    }

    public WebDriver createDiagnosticDriver() {
        return createDriver(true);
    }

    private WebDriver createDriver(boolean browserLoggingEnabled) {
        Path driverExecutable = properties.requireChromeDriverPath();
        ChromeDriverService driverService = new ChromeDriverService.Builder()
                .usingDriverExecutable(driverExecutable.toFile())
                .usingAnyFreePort()
                .build();
        ChromeOptions options = buildOptions(browserLoggingEnabled);
        try {
            WebDriver driver = new ChromeDriver(driverService, options);
            configureTimeouts(driver, properties.getPageLoadTimeout());
            return driver;
        } catch (RuntimeException exception) {
            log.error("创建 Chrome 浏览器会话失败", LogExceptionSanitizer.sanitize(exception));
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "创建浏览器会话失败，请检查 Chrome 与 ChromeDriver 版本及运行权限",
                    exception
            );
        }
    }

    private ChromeOptions buildOptions(boolean browserLoggingEnabled) {
        ChromeOptions options = new ChromeOptions();
        Path chromeBinary = properties.resolveChromeBinaryPath();
        if (chromeBinary != null) {
            options.setBinary(chromeBinary.toFile());
        }
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-extensions");
        options.addArguments("--window-size="
                + properties.getViewportWidth() + "," + properties.getViewportHeight());
        if (properties.isNoSandbox()) {
            options.addArguments("--no-sandbox");
        }
        if (browserLoggingEnabled) {
            LoggingPreferences loggingPreferences = new LoggingPreferences();
            loggingPreferences.enable(LogType.BROWSER, Level.ALL);
            options.setCapability("goog:loggingPrefs", loggingPreferences);
        }
        return options;
    }

    private void configureTimeouts(WebDriver driver, Duration pageLoadTimeout) {
        driver.manage().timeouts().pageLoadTimeout(pageLoadTimeout);
        driver.manage().timeouts().scriptTimeout(pageLoadTimeout);
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
    }
}
